package com.muoniumplayer.core.rendering.entities.impl;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import lombok.Getter;
import lombok.Setter;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.ChatAllowedCharacters;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.settings.ClientSettings;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.timing.Timer;

import java.awt.*;

public class TextField implements SharedConstants {
    @Getter
    private final int textFieldNumber;

    public float xPosition;
    public float yPosition;
    public float width;
    public float height;

    @Getter
    private String text = "";
    @Getter
    private int maxStringLength = 512;
    @Setter
    public String placeholder = "";
    public boolean isPassword;
    @Setter
    public TextChangedCallback callback = null;

    @Getter
    public int cursorPosition;
    @Getter
    public int selectionEnd;
    private int lineScrollOffset;
    private int cursorCounter;

    public boolean lmbPressed;
    public boolean dragging;
    private int dragStartChar;
    private int dragEndChar;

    @Setter
    private boolean drawLineUnder = true;
    @Setter
    private boolean canLoseFocus = true;
    @Getter
    private boolean isFocused;
    @Getter
    @Setter
    private boolean isEnabled = true;
    @Setter
    private boolean visible = true;

    public int enabledColor = 14737632;
    private int disabledColor = 7368816;
    @Setter
    private float lineOffset;
    @Setter
    private float wholeAlpha = 1;
    private float opacity;
    private float hoverAlpha;
    private Color lineColor;

    @Setter
    private CFontRenderer fontRenderer = FontManager.pf18;

    private Predicate<String> textPredicate = Predicates.alwaysTrue();
    private final Timer imePositionUpdateTimer = new Timer();
    private final Timer cursorForceShowTimer = new Timer();

    public interface TextChangedCallback {
        void onTextChanged(String newText);
    }

    public TextField(int number, float x, float y, int width, int height) {
        this.textFieldNumber = number;
        this.xPosition = x;
        this.yPosition = y;
        this.width = width;
        this.height = height;
        this.lineColor = new Color(160, 160, 160);
    }

    public void setText(String text) {
        this.text = text;
        if (this.callback != null)
            this.callback.onTextChanged(text);
        setCursorPositionEnd();
    }

    public String getSelectedText() {
        int start = Math.min(cursorPosition, selectionEnd);
        int end = Math.max(cursorPosition, selectionEnd);
        return text.substring(start, end);
    }

    public void writeText(String input) {
        String filtered = ChatAllowedCharacters.filterAllowedCharacters(input);
        int selStart = Math.min(cursorPosition, selectionEnd);
        int selEnd = Math.max(cursorPosition, selectionEnd);
        int availableSpace = maxStringLength - text.length() - (selEnd - selStart);

        StringBuilder newText = new StringBuilder();

        if (!text.isEmpty()) {
            newText.append(text, 0, selStart);
        }

        int charsToAdd = Math.min(availableSpace, filtered.length());
        newText.append(filtered, 0, charsToAdd);

        if (!text.isEmpty() && selEnd < text.length()) {
            newText.append(text.substring(selEnd));
        }

        String result = newText.toString();
        if (textPredicate.apply(result)) {
            text = result;
            if (this.callback != null)
                this.callback.onTextChanged(text);
            moveCursorBy(selStart - selectionEnd + charsToAdd);
        }
    }

    public void deleteFromCursor(int count) {
        if (text.isEmpty()) {
            return;
        }

        if (selectionEnd != cursorPosition) {
            writeText("");
            return;
        }

        boolean deleteBefore = count < 0;
        int deleteStart = deleteBefore ? cursorPosition + count : cursorPosition;
        int deleteEnd = deleteBefore ? cursorPosition : cursorPosition + count;

        StringBuilder newText = new StringBuilder();
        if (deleteStart >= 0) {
            newText.append(text, 0, deleteStart);
        }
        if (deleteEnd < text.length()) {
            newText.append(text.substring(deleteEnd));
        }

        text = newText.toString();
        if (this.callback != null)
            this.callback.onTextChanged(text);

        if (deleteBefore) {
            moveCursorBy(count);
        }
    }

    public void deleteWords(int count) {
        if (!text.isEmpty()) {
            if (selectionEnd != cursorPosition) {
                writeText("");
            } else {
                deleteFromCursor(getNthWordFromCursor(count) - cursorPosition);
            }
        }
    }

    public void setCursorPosition(int position) {
        cursorPosition = clamp_int(position, 0, text.length());
        dragStartChar = cursorPosition;
        dragEndChar = cursorPosition;
        setSelectionPos(cursorPosition);
        cursorForceShowTimer.reset();
    }

    public static int clamp_int(int num, int min, int max) {
        return num < min ? min : (Math.min(num, max));
    }

    public void setCursorPositionZero() {
        setCursorPosition(0);
    }

    public void setCursorPositionEnd() {
        setCursorPosition(text.length());
        dragStartChar = dragEndChar = text.length();
        cursorForceShowTimer.reset();
    }

    public void moveCursorBy(int offset) {
        setCursorPosition(selectionEnd + offset);
    }

    public void setSelectionPos(int position) {
        selectionEnd = clamp_int(position, 0, text.length());
    }

    public void updateCursorCounter() {
        cursorCounter++;
    }

    public int getNthWordFromCursor(int n) {
        return getNthWordFromPos(n, cursorPosition);
    }

    public int getNthWordFromPos(int n, int position) {
        return findWordBoundary(n, position, true);
    }

    private int findWordBoundary(int wordCount, int position, boolean skipSpaces) {
        int currentPos = position;
        boolean searchBackward = wordCount < 0;
        int absWordCount = Math.abs(wordCount);

        for (int i = 0; i < absWordCount; i++) {
            if (searchBackward) {
                
                while (skipSpaces && currentPos > 0 && text.charAt(currentPos - 1) == ' ') {
                    currentPos--;
                }
                while (currentPos > 0 && text.charAt(currentPos - 1) != ' ') {
                    currentPos--;
                }
            } else {
                
                int textLength = text.length();
                currentPos = text.indexOf(' ', currentPos);

                if (currentPos == -1) {
                    currentPos = textLength;
                } else {
                    while (skipSpaces && currentPos < textLength && text.charAt(currentPos) == ' ') {
                        currentPos++;
                    }
                }
            }
        }

        return currentPos;
    }

    public void selectAll() {
        this.setCursorPosition(0);
        this.setSelectionPos(text.length());
        this.dragStartChar = 0;
        this.dragEndChar = text.length();
    }

    public boolean textboxKeyTyped(char typedChar, int keyCode) {
        if (!isFocused) {
            return false;
        }

        if (KeyboardUtils.isKeyComboCtrlA(keyCode)) {
            this.selectAll();
            return true;
        }
        if (KeyboardUtils.isKeyComboCtrlC(keyCode)) {
            KeyboardUtils.setClipboardString(getSelectedText());
            return true;
        }
        if (KeyboardUtils.isKeyComboCtrlV(keyCode)) {
            if (isEnabled) {
                writeText(KeyboardUtils.getClipboardString());
            }
            return true;
        }
        if (KeyboardUtils.isKeyComboCtrlX(keyCode)) {
            KeyboardUtils.setClipboardString(getSelectedText());
            if (isEnabled) {
                writeText("");
            }
            return true;
        }

        switch (keyCode) {
            case Keyboard.KEY_BACK:
                if (isEnabled) {
                    if (KeyboardUtils.isCtrlKeyDown()) {
                        deleteWords(-1);
                    } else {
                        deleteFromCursor(-1);
                    }
                }
                return true;
            case Keyboard.KEY_DELETE:
                if (isEnabled) {
                    if (KeyboardUtils.isCtrlKeyDown()) {
                        deleteWords(1);
                    } else {
                        deleteFromCursor(1);
                    }
                }
                return true;
            case Keyboard.KEY_HOME:
                if (KeyboardUtils.isShiftKeyDown()) {
                    setSelectionPos(0);
                } else {
                    setCursorPositionZero();
                }
                return true;
            case Keyboard.KEY_END:
                if (KeyboardUtils.isShiftKeyDown()) {
                    setSelectionPos(text.length());
                } else {
                    setCursorPositionEnd();
                }
                return true;
            case Keyboard.KEY_LEFT:
                handleLeftKey();
                return true;
            case Keyboard.KEY_RIGHT:
                handleRightKey();
                return true;
            default:
                if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
                    if (isEnabled) {
                        writeText(Character.toString(typedChar));
                    }
                    return true;
                }
                return false;
        }
    }

    private void handleLeftKey() {
        if (KeyboardUtils.isShiftKeyDown()) {
            if (KeyboardUtils.isCtrlKeyDown()) {
                setSelectionPos(getNthWordFromPos(-1, selectionEnd));
            } else {
                int newPos = Math.max(0, selectionEnd - 1);
                setSelectionPos(newPos);
                if (dragStartChar > 0) {
                    cursorForceShowTimer.reset();
                    dragStartChar--;
                }
            }
        } else {
            if (KeyboardUtils.isCtrlKeyDown()) {
                setCursorPosition(getNthWordFromCursor(-1));
            } else {
                if (cursorPosition != selectionEnd)
                    setCursorPosition(Math.min(cursorPosition, selectionEnd));
                else
                    moveCursorBy(-1);
            }
        }
    }

    private void handleRightKey() {
        if (KeyboardUtils.isShiftKeyDown()) {
            if (KeyboardUtils.isCtrlKeyDown()) {
                setSelectionPos(getNthWordFromPos(1, selectionEnd));
            } else {
                int newPos = Math.min(text.length(), selectionEnd + 1);
                setSelectionPos(newPos);
                if (dragStartChar < text.length()) {
                    cursorForceShowTimer.reset();
                    dragStartChar++;
                }
            }
        } else {
            if (KeyboardUtils.isCtrlKeyDown()) {
                setCursorPosition(getNthWordFromCursor(1));
            } else {
                if (cursorPosition != selectionEnd)
                    setCursorPosition(Math.max(cursorPosition, selectionEnd));
                else
                    moveCursorBy(1);
            }
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        boolean isHovered = RenderSystem.isHovered(mouseX, mouseY, xPosition, yPosition, width, height);

        if (canLoseFocus) {
            if (!isHovered) {
                dragging = false;
                lineScrollOffset = 0;
            }
            setFocused(isHovered);
        }

        if (isFocused && isHovered && mouseButton == 0) {
            startDragging(mouseX);
            return true;
        }

        return canLoseFocus;
    }

    private void startDragging(double mouseX) {
        float relativeX = (float) (mouseX - xPosition);

        if (!text.isEmpty() && !dragging) {
            String displayText = getDisplayText();
            float charWidth = (float) getFontRenderer().getStringWidthD(displayText) / text.length();
            cursorForceShowTimer.reset();
            dragStartChar = (int) (relativeX / charWidth);
            dragStartChar = clamp_int(dragStartChar, 0, text.length());
            setCursorPosition(dragStartChar);
        }

        dragging = true;
    }

    private void updateDragging(int mouseX) {
        if (!isFocused || !dragging || text.isEmpty()) {
            return;
        }

        float relativeX = mouseX - xPosition;

        float charWidth = (float) getFontRenderer().getStringWidthD(text) / text.length();
        dragEndChar = (int) (relativeX / charWidth);
        dragEndChar = clamp_int(dragEndChar, 0, text.length());
        setSelectionPos(dragEndChar);
    }

    public void drawTextBox(int mouseX, int mouseY) {
        if (!visible) {
            return;
        }

        wholeAlpha = Math.min(Math.max(0, wholeAlpha), 1);
        opacity = Interpolations.interpolate(opacity, 1.0f, 0.05f) * wholeAlpha;

        double textScrollOffset = calculateTextScrollOffset();

        boolean isHovered = RenderSystem.isHovered(mouseX, mouseY, xPosition, yPosition, width, height);
        updateStyles(isHovered, mouseX, mouseY);

        if (drawLineUnder) {
            RenderSystem.drawRect(
                    xPosition, yPosition + height + lineOffset,
                    xPosition + width, yPosition + height + 0.5f + lineOffset,
                    RenderSystem.reAlpha(lineColor.getRGB(), wholeAlpha)
            );
        }

        if (dragging && !Mouse.isButtonDown(0)) {
            dragging = false;
        }

        if (dragging) {
            updateDragging(mouseX);
        }

        setupClipping();

        renderTextContent(textScrollOffset, isHovered);

        cleanupClipping();
    }

    private double calculateTextScrollOffset() {
        double offset = 0;
        double totalWidth = getFontRenderer().getStringWidthD(text);

        if (totalWidth > this.width) {
            int targetPos = Math.max(cursorPosition, selectionEnd);
            double widthToTarget = getFontRenderer().getStringWidthD(text.substring(0, targetPos));

            if (widthToTarget > this.width) {
                offset = widthToTarget - this.width;
            }

            int leftPos = Math.min(cursorPosition, selectionEnd);
            double widthToLeft = getFontRenderer().getStringWidthD(text.substring(0, leftPos));
            if (widthToLeft < offset) {
                offset = widthToLeft;
            }
        }

        return offset;
    }

    private void updateStyles(boolean isHovered, int mouseX, int mouseY) {

        if (lmbPressed && !Mouse.isButtonDown(0))
            lmbPressed = false;

        if (isFocused && !lmbPressed && !dragging && !isHovered && Mouse.isButtonDown(0)) {
            setFocused(false);
        }

        Color targetColor = (isHovered || isFocused) ?
                new Color(255, 133, 155) : new Color(180, 180, 180);
        lineColor = Interpolations.getColorAnimationState(lineColor, targetColor, 100f);

        float targetAlpha = (isHovered || isFocused) ? 0.4f : 0.3f;
        hoverAlpha = Interpolations.interpolateLinear(hoverAlpha, targetAlpha, 0.1f) * wholeAlpha;
    }

    private void setupClipping() {
        StencilClipManager.beginClip(() -> RenderSystem.drawRect(xPosition - 1, yPosition - 4,
                xPosition + width + 1, yPosition + height + 4, -1));
    }

    private void cleanupClipping() {
        StencilClipManager.endClip();
    }

    private void renderTextContent(double scrollOffset, boolean isHovered) {
        String displayText = getDisplayText();
        float posX = xPosition;
        float posY = yPosition;
        
        if (text.isEmpty() && !placeholder.isEmpty() && !isFocused) {
            getFontRenderer().drawString(placeholder, posX, getRenderOffsetY(),
                    applyAlpha(enabledColor, hoverAlpha));
            return;
        }

        if (hasSelection()) {
            renderSelection(displayText, scrollOffset);
        }

        if (!displayText.isEmpty()) {
            int textColor = isFocused ? enabledColor : disabledColor;
            int alpha = (textColor >> 24) & 255;
            getFontRenderer().drawString(displayText, posX - (float) scrollOffset,
                    getRenderOffsetY(),
                    RenderSystem.reAlpha(textColor, alpha * RenderSystem.DIVIDE_BY_255 * wholeAlpha));
        }

        if (shouldDrawCursor() && !hasSelection()) {
            renderCursor(displayText, posX, getRenderOffsetY(), scrollOffset);
        }
    }

    private String getDisplayText() {
        return isPassword ? new String(new char[text.length()]).replace('\0', '*') : text;
    }

    private boolean hasSelection() {
        return dragStartChar != dragEndChar;
    }

    private boolean shouldDrawCursor() {
        int visibleCursorPos = cursorPosition - lineScrollOffset;
        return isFocused && (cursorCounter / 6) % 2 == 0 &&
                visibleCursorPos >= 0 && visibleCursorPos <= text.length();
    }

    private double getRenderOffsetY() {
        return yPosition + height * .5 - getFontRenderer().getFontHeight() * .5;
    }

    private void renderSelection(String displayText, double scrollOffset) {
        int lowestChar = Math.min(dragStartChar, dragEndChar);
        int highestChar = Math.max(dragStartChar, dragEndChar);

        lowestChar = clamp_int(lowestChar, 0, text.length());
        highestChar = clamp_int(highestChar, 0, text.length());

        if (lowestChar < highestChar) {
            float startX = (float) (xPosition + getFontRenderer().getStringWidthD(
                                displayText.substring(0, lowestChar)) - (float) scrollOffset);
            float endX = (float) (startX + getFontRenderer().getStringWidthD(
                                displayText.substring(lowestChar, highestChar)) + 1f);

            RenderSystem.drawRect(startX, getRenderOffsetY() - 1, endX, getRenderOffsetY() + getFontRenderer().getFontHeight() + 1,
                    applyAlpha(new Color(196, 225, 245).getRGB(), wholeAlpha));
            api.getGLStateManager().color(1, 1, 1, 1);
        }
    }

    double animatedCursorX = -1;

    private void renderCursor(String displayText, double posX, double posY, double scrollOffset) {
        String textBeforeCursor = dragStartChar > 0 ?
                displayText.substring(0, dragStartChar) : "";
        float cursorX = (float) (xPosition + getFontRenderer().getStringWidthD(textBeforeCursor) - (float) scrollOffset);

        long l = System.currentTimeMillis() / 5 % 255;
        int alpha = (int) Math.min(255,
                (l > 127 ?
                        (255 - l) :
                        l) * 2);

        if (animatedCursorX == -1)
            animatedCursorX = cursorX;
        animatedCursorX = Interpolations.interpolate(animatedCursorX, cursorX, .4f);

        if (alpha > 127 || !cursorForceShowTimer.isDelayed(750)) {
            RenderSystem.drawRect(animatedCursorX, posY - 2, animatedCursorX + 0.5f,
                    posY + getFontRenderer().getFontHeight() + 2, 0xffcdcbcd);
        }
    }

    private int applyAlpha(int color, float alpha) {
        float originalAlpha = ((color >> 24) & 0xFF) / 255f;
        Color c = new Color(color, true);
        return new Color(
                c.getRed() / 255f,
                c.getGreen() / 255f,
                c.getBlue() / 255f,
                originalAlpha * alpha
        ).getRGB();
    }

    public void setMaxStringLength(int length) {
        maxStringLength = length;
        if (text.length() > length) {
            text = text.substring(0, length);
            if (this.callback != null)
                this.callback.onTextChanged(text);
        }
    }

    public void setPredicate(Predicate<String> predicate) {
        textPredicate = predicate;
    }

    public void setTextColor(int color) {
        enabledColor = color;
    }

    public void setDisabledTextColour(int color) {
        disabledColor = color;
    }

    public void setFocused(boolean focused) {
        if (focused && !isFocused) {
            cursorForceShowTimer.reset();
            cursorCounter = 0;
            Keyboard.enableRepeatEvents(true);
        }

        if (!focused && isFocused) {
            Keyboard.enableRepeatEvents(false);
        }

        isFocused = focused;
    }

    public boolean getVisible() {
        return visible;
    }

    public int getWidth() {
        return (int) (width);
    }

    public void setPosition(double x, double y) {
        xPosition = (float) x;
        yPosition = (float) y;
    }

    public void setBounds(double w, double h) {
        width = (float) w;
        height = (float) h;
    }

    private CFontRenderer getFontRenderer() {
        if (fontRenderer == null ||
                (fontRenderer.sizePx == FontManager.pf18.sizePx && fontRenderer != FontManager.pf18)) {
            fontRenderer = FontManager.pf18;
        }
        return fontRenderer;
    }

}