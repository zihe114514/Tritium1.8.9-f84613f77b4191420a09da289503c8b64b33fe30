package com.muoniumplayer.core.screens.ncm;

import org.lwjgl.input.Mouse;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.animation.Easing;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.utils.cursor.CursorUtils;

/**
 * 全屏歌词页左上角的「A-Z」逐字特效快捷面板。
 *
 * <p>为什么单独做一个入口：这两项（逐字发光上限、逐字放大程度）只影响全屏歌词页，边调边看才调得准。
 * 内置 HUD 编辑器里也有同样的滑块（{@link HudSetting#FULLSCREEN_GLOW} /
 * {@link HudSetting#CURRENT_WORD_SCALE}），但那是另一个界面，看不到全屏歌词的实际效果。</p>
 *
 * <p>实时预览不需要任何额外机制：渲染器每帧直接读 {@link HudConfig} 的静态字段，拖动滑块的同一帧
 * 就会生效。落盘只在松开鼠标时发生一次，避免拖动过程中反复写文件。</p>
 */
final class LyricStyleQuickPanel implements SharedRenderingConstants, SharedConstants {

    /** 面板里的滑块，顺序即显示顺序。 */
    private static final HudSetting[] SETTINGS = {
            HudSetting.FULLSCREEN_GLOW,
            HudSetting.CURRENT_WORD_SCALE
    };

    private static final double MARGIN = 12.0;
    private static final double BUTTON_SIZE = 24.0;
    private static final double PANEL_WIDTH = 178.0;
    private static final double PANEL_PADDING = 10.0;
    private static final double TITLE_HEIGHT = 18.0;
    private static final double ROW_HEIGHT = 30.0;
    private static final double TRACK_HEIGHT = 4.0;

    private boolean expanded;
    private float expandAnimation;
    private int draggingIndex = -1;
    private boolean pendingSave;

    /** 上一帧的按钮/面板位置，供 {@code consumeClick} 复用（点击链路拿不到布局参数）。 */
    private double anchorX = Double.NaN;
    private double anchorY = Double.NaN;
    private float lastAlpha;

    void render(double mouseX, double mouseY, double posX, double posY, float alpha) {
        this.anchorX = posX + MARGIN;
        this.anchorY = posY + MARGIN;
        this.lastAlpha = alpha;

        // 面板收起时不再跟随鼠标，拖动状态也要清掉，否则收起后仍然会改数值。
        if (!expanded && draggingIndex >= 0) {
            releaseDrag();
        }

        updateDrag(mouseX, mouseY);

        expandAnimation = Interpolations.interpolate(expandAnimation, expanded ? 1f : 0f, .22f);

        renderPanel(mouseX, mouseY, alpha);
        renderButton(mouseX, mouseY, alpha);
    }

    /**
     * @return true 表示这次点击已被本面板吃掉，调用方不应再往下派发
     */
    boolean consumeClick(double mouseX, double mouseY, int mouseButton) {
        if (Double.isNaN(anchorX) || lastAlpha <= .5f) {
            return false;
        }

        if (isInside(mouseX, mouseY, anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE)) {
            if (mouseButton == 0) {
                expanded = !expanded;
                if (!expanded) {
                    releaseDrag();
                }
            }
            return true;
        }

        if (!expanded) {
            return false;
        }

        double panelTop = panelTop();
        if (!isInside(mouseX, mouseY, anchorX, panelTop, PANEL_WIDTH, panelHeight())) {
            // 点面板外面就收起来，并且这一下不吃掉：让它照常落到下面的控件上。
            expanded = false;
            releaseDrag();
            return false;
        }

        if (mouseButton == 0) {
            for (int i = 0; i < SETTINGS.length; i++) {
                double trackY = trackY(panelTop, i);
                // 命中范围比轨道本身高一些，4 像素的细条太难点。
                if (isInside(mouseX, mouseY, trackLeft(), trackY - 6, trackWidth(), TRACK_HEIGHT + 12)) {
                    draggingIndex = i;
                    applyDrag(mouseX);
                    break;
                }
            }
        }
        return true;
    }

    private void updateDrag(double mouseX, double mouseY) {
        if (draggingIndex < 0) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            releaseDrag();
            return;
        }
        applyDrag(mouseX);
    }

    private void applyDrag(double mouseX) {
        if (draggingIndex < 0 || draggingIndex >= SETTINGS.length) {
            return;
        }
        HudSetting setting = SETTINGS[draggingIndex];
        double fraction = (mouseX - trackLeft()) / Math.max(1.0, trackWidth());
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        setting.setValue((float) (setting.getMin() + (setting.getMax() - setting.getMin()) * fraction));
        pendingSave = true;
    }

    private void releaseDrag() {
        draggingIndex = -1;
        if (pendingSave) {
            pendingSave = false;
            HudConfig.save();
        }
    }

    private void renderButton(double mouseX, double mouseY, float alpha) {
        boolean hover = isInside(mouseX, mouseY, anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE);
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }

        float backgroundAlpha = alpha * (expanded ? .34f : (hover ? .26f : .16f));
        roundedRect(anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE, 7, hexColor(1f, 1f, 1f, backgroundAlpha));

        String label = "A-Z";
        double textX = anchorX + (BUTTON_SIZE - FontManager.pf12bold.getStringWidthD(label)) * .5;
        double textY = anchorY + (BUTTON_SIZE - FontManager.pf12bold.getFontHeight()) * .5;
        FontManager.pf12bold.drawString(label, textX, textY,
                hexColor(1f, 1f, 1f, alpha * (hover || expanded ? 1f : .82f)));
    }

    private void renderPanel(double mouseX, double mouseY, float alpha) {
        if (expandAnimation <= .01f) {
            return;
        }

        double eased = Easing.EASE_IN_OUT_QUAD.getFunction().apply((double) expandAnimation);
        float panelAlpha = (float) (alpha * eased);
        double panelTop = panelTop();
        double height = panelHeight();

        api.getGLStateManager().pushMatrix();
        // 从按钮左上角展开：只放大不平移，收起时看起来是被按钮"吸"回去的。
        scaleAtPos(anchorX, panelTop, .90 + .10 * eased);
        try {
            roundedRect(anchorX, panelTop, PANEL_WIDTH, height, 10, hexColor(.06f, .07f, .09f, panelAlpha * .88f));
            roundedOutline(anchorX, panelTop, PANEL_WIDTH, height, 10, .8,
                    new java.awt.Color(1f, 1f, 1f, Math.max(0f, Math.min(1f, panelAlpha * .16f))));

            FontManager.pf14bold.drawString("逐字特效", anchorX + PANEL_PADDING,
                    panelTop + PANEL_PADDING, hexColor(1f, 1f, 1f, panelAlpha));

            for (int i = 0; i < SETTINGS.length; i++) {
                renderRow(SETTINGS[i], i, panelTop, mouseX, mouseY, panelAlpha);
            }
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    private void renderRow(HudSetting setting, int index, double panelTop,
                           double mouseX, double mouseY, float panelAlpha) {
        double rowY = panelTop + PANEL_PADDING + TITLE_HEIGHT + index * ROW_HEIGHT;
        double trackY = trackY(panelTop, index);
        double left = trackLeft();
        double width = trackWidth();

        FontManager.pf12bold.drawString(setting.getLabel(), left, rowY,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));

        String value = String.format("%.2f", setting.getValue());
        FontManager.pf12bold.drawString(value,
                left + width - FontManager.pf12bold.getStringWidthD(value), rowY,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        boolean hover = draggingIndex == index
                || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }

        double range = Math.max(1.0e-4, setting.getMax() - setting.getMin());
        double fraction = Math.max(0.0, Math.min(1.0, (setting.getValue() - setting.getMin()) / range));

        roundedRect(left, trackY, width, TRACK_HEIGHT, TRACK_HEIGHT * .5,
                hexColor(1f, 1f, 1f, panelAlpha * .18f));
        double fillWidth = width * fraction;
        if (fillWidth > .5) {
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            roundedRect(left, trackY, fillWidth, TRACK_HEIGHT,
                    Math.min(TRACK_HEIGHT * .5, fillWidth * .5),
                    reAlpha(accent, panelAlpha));
        }

        double knobRadius = hover ? 5.0 : 4.0;
        roundedRect(left + fillWidth - knobRadius, trackY + TRACK_HEIGHT * .5 - knobRadius,
                knobRadius * 2, knobRadius * 2, knobRadius, hexColor(1f, 1f, 1f, panelAlpha));
    }

    private double panelTop() {
        return anchorY + BUTTON_SIZE + 6;
    }

    private double panelHeight() {
        return PANEL_PADDING * 2 + TITLE_HEIGHT + SETTINGS.length * ROW_HEIGHT;
    }

    private double trackLeft() {
        return anchorX + PANEL_PADDING;
    }

    private double trackWidth() {
        return PANEL_WIDTH - PANEL_PADDING * 2;
    }

    private double trackY(double panelTop, int index) {
        return panelTop + PANEL_PADDING + TITLE_HEIGHT + index * ROW_HEIGHT + 16;
    }

    private boolean isInside(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
