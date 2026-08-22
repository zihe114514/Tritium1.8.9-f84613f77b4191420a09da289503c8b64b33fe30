package com.muoniumplayer.core.widget.impl;

import today.opai.api.enums.EnumChatColor;
import today.opai.api.enums.EnumModuleCategory;
import today.opai.api.features.ExtensionModule;
import today.opai.api.features.ExtensionWidget;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.modules.values.BooleanValue;
import today.opai.api.interfaces.modules.values.ColorValue;
import today.opai.api.interfaces.modules.values.ModeValue;
import today.opai.api.interfaces.modules.values.NumberValue;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.reflection.Reflection;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricDuetGroups;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.ClientSettings;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.WidgetWrapper;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * @author IzumiiKonata
 * Date: 2025/2/14 20:34
 */
public class MusicLyricsWidget extends ExtensionModule implements SharedConstants, SharedRenderingConstants, EventHandler {
    public ModeValue alignMode = api.getValueManager().createModes("Align Mode", "Center", new String[]{ "Left", "Center", "Right" });

    public enum AlignMode {
        Left,
        Center,
        Right
    }

    public NumberValue width = api.getValueManager().createDouble("Width", 450, 225, 900, 5);
    public NumberValue height = api.getValueManager().createDouble("Height", 120, 60, 480, 5);
    public NumberValue lyricHeight = api.getValueManager().createDouble("Lyric Height", 20.0, 14.0, 50.0, 0.5);
    public ColorValue lyricColor = api.getValueManager().createColor("Lyric Color", Color.WHITE);
    public ColorValue currentLyricColor = api.getValueManager().createColor("Current Lyric Color", Color.WHITE);
    public BooleanValue shadow = api.getValueManager().createBoolean("Shadow", false);
    public BooleanValue singleLine = api.getValueManager().createBoolean("Single Line Mode", false);
    public BooleanValue showTranslation = api.getValueManager().createBoolean("Show Translation", true);
    public BooleanValue graceScroll = api.getValueManager().createBoolean("Elegant Scrolling", true);
    public BooleanValue showRoman = api.getValueManager().createBoolean("Show Romanization in Japanese songs", false);
    public BooleanValue dynIsland = api.getValueManager().createBoolean("Dynamic Island Lyrics", false);
    /** AMLL TTML 词库(人工校对的逐字歌词)优先于任何平台歌词。关掉后完全回到旧的解析链。 */
    public BooleanValue preferAmllLyrics = api.getValueManager().createBoolean("Prefer AMLL Word-by-Word Lyrics", true);
    /** AMLL 词库没有收录时,再用 QQ 音乐的 QRC 逐字歌词;仍然优先于任何行级歌词。 */
    public BooleanValue preferQqQrcLyrics = api.getValueManager().createBoolean("Prefer QQ Word-by-Word Lyrics", true);

    public ExtensionWidget widget;
    WidgetWrapper.WidgetPosSizeInterface wpsInterface;

    // The HUD editor reuses the production renderer at an editor-provided position.
    // Keeping this state local avoids changing the persisted HUD configuration while previewing.
    private boolean editorPreviewActive;
    private float editorPreviewX;
    private float editorPreviewY;
    private float editorPreviewScale;
    
    public MusicLyricsWidget() {
        super("Music Lyrics", "Show lyrics.", EnumModuleCategory.VISUAL);

        graceScroll.setHiddenPredicate(() -> singleLine.getValue());
        showRoman.setHiddenPredicate(() -> !showTranslation.getValue());
        dynIsland.setHiddenPredicate(() -> !Reflection.DYNAMIC_ISLAND_SUPPORTED);
        
        this.addValues(alignMode, width, height, lyricHeight, lyricColor, currentLyricColor, shadow, singleLine, graceScroll, showRoman, dynIsland, preferAmllLyrics, preferQqQrcLyrics);

        Tuple<ExtensionWidget, WidgetWrapper.WidgetPosSizeInterface> wrapped = WidgetWrapper.createWrapper(this, this::onRender);
        this.widget = wrapped.getA();
        this.wpsInterface = wrapped.getB();
        this.setEventHandler(this);
    }

    /** Applies the lyric appearance settings stored by the built-in HUD editor. */
    public void loadHudEditorSettings() {
        lyricColor.setValue(new Color(HudConfig.lyricColorRgb, true));
        currentLyricColor.setValue(new Color(HudConfig.currentLyricColorRgb, true));
    }

    /**
     * Renders the same animated desktop-lyric path used in game, but at the
     * position selected in the HUD editor. The persisted HudConfig position is
     * intentionally not touched by this preview.
     */
    public void renderEditorPreview(float x, float y, float scale) {
        if (this.widget == null) {
            return;
        }

        editorPreviewActive = true;
        editorPreviewX = x;
        editorPreviewY = y;
        editorPreviewScale = scale;
        try {
            this.widget.render();
        } finally {
            editorPreviewActive = false;
        }
    }

    private static final double CONTENT_HORIZONTAL_PADDING = 12.0;
    private static final double PRIMARY_LINE_SPACING = 3.0;
    private static final double SECONDARY_LINE_SPACING = 2.0;
    private static final double PRIMARY_TO_SECONDARY_SPACING = 5.0;
    private static final double ROW_SPACING = 10.0;
    private static final int KARAOKE_FEATHER_STEPS = 10;

    /**
     * Seeks must not reuse the animated positions from an unrelated timestamp.
     * Marking the cached positions invalid lets the next frame lay every visible line
     * out at its new location before it resumes the smooth follow animation.
     */
    public static void resetProgress(float progress) {
        if (CloudMusic.lyrics.isEmpty()) return;

        try {
            CloudMusic.setLyricsProgress(progress);
            synchronized (CloudMusic.lyrics) {
                for (LyricLine line : CloudMusic.lyrics) {
                    line.offsetY = Double.MIN_VALUE;
                    line.lineAlpha = .4f;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static boolean hasSecondaryLyrics() {
        return CloudMusic.hasSecondaryLyrics();
    }

    public static String getSecondaryLyrics(LyricLine bean) {
        return CloudMusic.getSecondaryLyrics(bean);
    }

    public void onRender() {

        if (!shouldRender()) {
            return;
        }

        // HUD 位置 + 缩放（HudConfig，由 GuiHudEditor 拖拽/滚轮调整）。
        // 位置公式与参考实现一致：pixel = pos * (screen - baseSize * scale)；缩放围绕左上锚点等比进行。
        float hudScale = editorPreviewActive ? editorPreviewScale : HudConfig.lyricScale;
        float baseW = this.width.getValue().floatValue();
        float baseH = this.height.getValue().floatValue();
        float hudX = editorPreviewActive
                ? editorPreviewX
                : (float) (HudConfig.lyricX * (getWidth() - baseW * hudScale));
        float hudY = editorPreviewActive
                ? editorPreviewY
                : (float) (HudConfig.lyricY * (getHeight() - baseH * hudScale));
        wpsInterface.setX(hudX);
        wpsInterface.setY(hudY);

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(hudX, hudY, 0);
        api.getGLStateManager().scale(hudScale, hudScale, 1);
        api.getGLStateManager().translate(-hudX, -hudY, 0);

        float songProgress = CloudMusic.player.getCurrentTimeMillis();

        boolean shouldNotDisplayOtherLyrics = this.singleLine.getValue();

        handleSingleLineMode(shouldNotDisplayOtherLyrics);
        if (Reflection.DYNAMIC_ISLAND_SUPPORTED && dynIsland.getValue()) {
//            String property = System.getProperty("ncm.dynIslandLyrics");
            LyricLine currentLine = CloudMusic.currentLyric;
            if (currentLine != null) {

                if (currentLine.isBreakLine) {
                    int i = CloudMusic.lyrics.indexOf(currentLine);

                    if (i > 0)
                        currentLine = CloudMusic.lyrics.get(i - 1);
                    else if (i + 1 < CloudMusic.lyrics.size())
                        currentLine = CloudMusic.lyrics.get(i + 1);
                }

                if (CloudMusic.haveNoWords) {
                    System.setProperty("ncm.dynIslandLyrics", EnumChatColor.WHITE + currentLine.getLyric());
                } else {
                    WordInfo wordInfo = calculateCurrentWordInfo(currentLine, songProgress);

                    String left = EnumChatColor.WHITE + "";
                    String right = EnumChatColor.GRAY + "";

                    int leftEndIdx = wordInfo.currentIndex;
                    int rightStartIdx = wordInfo.currentIndex + 1;

//                    if (rightStartIdx == 1) {
//                        LyricLine.Word current = currentLine.words.get(wordInfo.currentIndex);
//                        double value = (songProgress - current.timestamp) / (double) (current.duration);
//
//                        if (value < 0) {
//                            rightStartIdx -= 1;
//                            leftEndIdx -= 1;
//                        }
//                    }

                    for (int i = 0; i <= leftEndIdx - 1; i++) {
                        left += currentLine.words.get(i).word;
                    }

                    LyricLine.Word current = currentLine.words.get(wordInfo.currentIndex);
                    double value = Math.max(0, Math.min(1, (songProgress - current.timestamp) / (double) (current.duration)));

                    String word = current.word;

                    if (value > 0) {
                        int endIndex = word.length() > 1 ? (int) (word.length() * value) : 1;
                        left += word.substring(0, endIndex);
                        right += word.substring(endIndex);
                    } else {
                        right += word;
                    }

                    for (int i = rightStartIdx; i < currentLine.words.size(); i++) {
                        right += currentLine.words.get(i).word;
                    }

                    System.setProperty("ncm.dynIslandLyrics", left + right);
                }
            } else {
                System.setProperty("ncm.dynIslandLyrics", "");
            }
        } else {
            System.setProperty("ncm.dynIslandLyrics", "");

            api.getGLStateManager().pushMatrix();

            StencilClipManager.beginClip(() -> Rect.draw(wpsInterface.getX() - 2, wpsInterface.getY(), wpsInterface.getWidth() + 4, wpsInterface.getHeight(), -1));

            renderAllLyrics(shouldNotDisplayOtherLyrics, songProgress);

            cleanupRender();
            StencilClipManager.endClip();
        }

        if (ClientSettings.DEBUG_MODE) {
            LyricLine currentLine = CloudMusic.currentLyric;
            if (currentLine != null && !CloudMusic.haveNoWords) {
                WordInfo wordInfo = calculateCurrentWordInfo(currentLine, songProgress);

                LyricLine.Word current = currentLine.words.get(wordInfo.currentIndex);
                FontManager.pf28bold.drawStringWithShadow("Current word: " + current.word, 100, 100, -1);
                double value = (songProgress - current.timestamp) / (double) (current.duration);
                FontManager.pf28bold.drawStringWithShadow("Perc: " + value, 100, 120, -1);
                FontManager.pf28bold.drawStringWithShadow("Dur: " + current.duration, 100, 140, -1);
                FontManager.pf28bold.drawStringWithShadow("Pos: " + (songProgress - current.timestamp), 100, 160, -1);
            }
        }

        api.getGLStateManager().popMatrix();
    }

    private boolean shouldRender() {
        return CloudMusic.player != null && !CloudMusic.player.isFinished() && !CloudMusic.lyrics.isEmpty();
    }

    private void handleSingleLineMode(boolean shouldNotDisplayOtherLyrics) {
        if (shouldNotDisplayOtherLyrics && CloudMusic.currentLyric == null) {
            if (!CloudMusic.lyrics.isEmpty()) {
                CloudMusic.currentLyric = CloudMusic.lyrics.get(0);
            }
        }
    }

    private void renderAllLyrics(boolean singleLineMode, float songProgress) {
        synchronized (CloudMusic.lyrics) {
            if (CloudMusic.lyrics.isEmpty()) {
                return;
            }

            int currentIndex = CloudMusic.lyrics.indexOf(CloudMusic.currentLyric);
            if (currentIndex < 0) {
                return;
            }

            // A duet section is two (rarely three) lines whose timelines genuinely overlap. All of
            // them are the current line at once, so they are laid out and centered as one block:
            // highlighting only one of them makes the other look already-sung, and the "current"
            // line would visibly bounce between the two singers.
            int groupStart = LyricDuetGroups.groupStart(CloudMusic.lyrics, currentIndex);
            int groupEnd = LyricDuetGroups.groupEnd(CloudMusic.lyrics, currentIndex);
            int groupSize = groupEnd - groupStart + 1;

            LyricLayout[] groupLayouts = new LyricLayout[groupSize];
            double blockHeight = 0.0;
            double blockAdvance = 0.0;
            for (int i = 0; i < groupSize; i++) {
                groupLayouts[i] = createLyricLayout(CloudMusic.lyrics.get(groupStart + i));
                // The last row contributes its glyph height only: its trailing line spacing is not
                // part of what the eye reads as the block.
                blockHeight = blockAdvance + groupLayouts[i].visualHeight;
                blockAdvance += groupLayouts[i].rowHeight;
            }

            double viewportTop = wpsInterface.getY();
            double viewportBottom = viewportTop + wpsInterface.getHeight();
            // Keep the active lyric block centered. This is especially important when
            // a narrow HUD wraps it into multiple physical text rows.
            double blockTop = viewportTop + (wpsInterface.getHeight() - blockHeight) * .5;

            if (singleLineMode) {
                // Single-line mode still shows a whole duet: the point of the mode is to hide the
                // lines that are not being sung, and in a duet both of them are.
                double y = blockTop;
                for (int i = 0; i < groupSize; i++) {
                    renderLyricLine(CloudMusic.lyrics.get(groupStart + i), true, y,
                            groupLayouts[i], songProgress);
                    y += groupLayouts[i].rowHeight;
                }
                return;
            }

            // The viewport height, not a fixed number setting, decides how many rows are
            // visible. Lines just outside the clip are kept in the layout pass so their
            // motion and edge fade remain continuous while scrolling.
            double fadePadding = getEdgeFadeSize();
            double y = blockTop;
            for (int i = groupStart - 1; i >= 0; i--) {
                LyricLayout layout = createLyricLayout(CloudMusic.lyrics.get(i));
                y -= layout.rowHeight;
                if (y + layout.visualHeight < viewportTop - fadePadding) {
                    break;
                }
                renderLyricLine(CloudMusic.lyrics.get(i), false, y, layout, songProgress);
            }

            y = blockTop;
            for (int i = 0; i < groupSize; i++) {
                renderLyricLine(CloudMusic.lyrics.get(groupStart + i), true, y,
                        groupLayouts[i], songProgress);
                y += groupLayouts[i].rowHeight;
            }

            for (int i = groupEnd + 1; i < CloudMusic.lyrics.size(); i++) {
                if (y > viewportBottom + fadePadding) {
                    break;
                }
                LyricLine line = CloudMusic.lyrics.get(i);
                LyricLayout layout = createLyricLayout(line);
                renderLyricLine(line, false, y, layout, songProgress);
                y += layout.rowHeight;
            }
        }
    }

    private void renderLyricLine(LyricLine line, boolean isCurrent,
                                 double targetY, LyricLayout layout, float songProgress) {
        double animatedY = updateLineY(line, targetY);
        double edgeAlpha = calculateEdgeAlpha(animatedY, layout.visualHeight);
        if (edgeAlpha <= .01) {
            return;
        }

        LyricRenderInfo renderInfo = new LyricRenderInfo();
        renderInfo.yPosition = animatedY;
        renderInfo.visibilityAlpha = edgeAlpha;
        updateLyricAnimation(line, isCurrent, edgeAlpha);
        renderLyricText(line, renderInfo, isCurrent, layout, songProgress);
    }

    private double updateLineY(LyricLine line, double targetY) {
        double resetDistance = Math.max(wpsInterface.getHeight() * 1.5, 160.0);
        if (line.offsetY == Double.MIN_VALUE || Math.abs(line.offsetY - targetY) > resetDistance) {
            line.offsetY = targetY;
        } else if (this.graceScroll.getValue()) {
            // Higher smoothness follows more gently while still converging after a line change.
            float follow = 0.35f - HudConfig.scrollSmoothness * 0.26f;
            line.offsetY = Interpolations.interpolate(line.offsetY, targetY, follow);
        } else {
            line.offsetY = targetY;
        }
        return line.offsetY;
    }

    private double calculateEdgeAlpha(double y, double visualHeight) {
        if (!HudConfig.lyricEdgeFadeEnabled) {
            return 1.0;
        }
        double top = wpsInterface.getY();
        double bottom = top + wpsInterface.getHeight();
        double center = y + visualHeight * .5;
        double distanceToEdge = Math.min(center - top, bottom - center);
        double progress = Math.max(0.0, Math.min(1.0, distanceToEdge / getEdgeFadeSize()));
        // smoothstep removes the visible boundary created by a linear alpha ramp.
        return progress * progress * (3.0 - 2.0 * progress);
    }

    private double getEdgeFadeSize() {
        // Do not let the fade consume more than half of a compact viewport.
        return Math.max(6.0, Math.min(HudConfig.edgeFadeSize, wpsInterface.getHeight() * .48));
    }

    private LyricLayout createLyricLayout(LyricLine line) {
        // Only real word timestamps may enter the KTV renderer. A plain LRC can carry
        // word tokens after parsing, but without durations every token resolves at once and
        // would incorrectly recolour the whole line.
        KaraokeLayoutBuilder.Layout karaokeLayout = line.hasTimedWords() ? createKaraokeLayout(line) : null;
        String[] primaryLines = karaokeLayout == null
                ? fitText(getFontRenderer(), line.getLyric()) : karaokeLayout.primaryLines;
        String secondaryLyric = hasSecondaryLyrics() ? getSecondaryLyrics(line) : "";
        String[] secondaryLines = secondaryLyric.isEmpty()
                ? new String[0] : fitText(getSmallFontRenderer(), secondaryLyric);
        return new LyricLayout(primaryLines, secondaryLines, karaokeLayout);
    }

    /**
     * Keeps every YRC word's timing while producing the same responsive physical rows
     * used by the HUD. A word that is wider than the HUD is split into timed fragments,
     * so KTV highlighting never overflows when the widget is resized.
     */
    private KaraokeLayoutBuilder.Layout createKaraokeLayout(LyricLine line) {
        return KaraokeLayoutBuilder.build(getFontRenderer(), line, getContentWidth());
    }

    private String[] fitText(CFontRenderer font, String text) {
        return KaraokeLayoutBuilder.fitText(font, text, getContentWidth());
    }
    private double getContentWidth() {
        return Math.max(40.0, wpsInterface.getWidth() - CONTENT_HORIZONTAL_PADDING * 2.0);
    }

    private double getContentLeft() {
        return wpsInterface.getX() + CONTENT_HORIZONTAL_PADDING;
    }
    private void updateLyricAnimation(LyricLine line, boolean isCurrent, double edgeAlpha) {
        float idleAlpha = (float) (HudConfig.normalOpacity * (.72 + .28 * edgeAlpha));
        line.lineAlpha = Interpolations.interpolate(
                line.lineAlpha,
                isCurrent ? 1f : idleAlpha,
                isCurrent ? .14f : .10f
        );
    }

    private void renderLyricText(LyricLine line, LyricRenderInfo renderInfo,
                                 boolean isCurrent, LyricLayout layout,
                                 float songProgress) {
        // A KaraokeLayout is created only from parsed word-level timing. Whenever that
        // timing is available the current OSD line must use the one KTV renderer; an
        // optional legacy effect must never be able to replace its left-to-right fill.
        boolean useKaraoke = isCurrent && layout.karaokeLayout != null;
        int effectAlpha = (int) (255 * line.lineAlpha * renderInfo.visibilityAlpha);

        if (useKaraoke) {
            // Base text, glow, stencil clips and moving highlight must share one transform.
            // Otherwise current-line scaling makes the coloured layer drift away from the glyphs.
            renderKaraokeBlock(layout, renderInfo.yPosition, songProgress, effectAlpha);
        } else {
            renderWrappedPrimary(layout.primaryLines, renderInfo.yPosition,
                    getConfiguredLyricColor(line, effectAlpha), isCurrent, songProgress, true);
        }

        if (layout.secondaryLines.length > 0) {
            int secondaryAlpha = (int) (255 * line.lineAlpha * renderInfo.visibilityAlpha
                    * HudConfig.secondaryOpacity);
            double secondaryY = renderInfo.yPosition + layout.primaryVisualHeight
                    + PRIMARY_TO_SECONDARY_SPACING;
            renderWrappedSecondary(layout.secondaryLines, secondaryY,
                    getConfiguredLyricColor(line, secondaryAlpha), isCurrent, songProgress);
        }
    }

    private void renderKaraokeBlock(LyricLayout layout, double y,
                                     float songProgress, int effectAlpha) {
        double centerX = getContentLeft() + getContentWidth() * .5;
        double centerY = y + layout.primaryVisualHeight * .5;
        LyricVisualStyle visualStyle = getLyricVisualStyle(true, songProgress);
        double scale = visualStyle.scale;

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(scale, scale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            // Keep the unsung lyric strictly on the base colour. The active colour is
            // painted only by the clipped per-character KTV layers below; applying an
            // active-colour glow to the complete row makes the whole sentence look sung.
            renderWrappedPrimaryUnscaled(layout.primaryLines, y,
                    getBaseLyricColor((int) (effectAlpha * .70f)));
            renderKaraokeProgress(layout.karaokeLayout, y, songProgress, effectAlpha, visualStyle);
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    private void renderWrappedPrimaryUnscaled(String[] lines, double y, int color) {
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i];
            double lineY = y + i * (getFontRenderer().getHeight() + PRIMARY_LINE_SPACING);
            bigFrString(text, calculateAlignmentX(text, this.alignMode.getValue()), lineY, color);
        }
    }

    /**
     * Paints the active OSD lyric from the timed-word clock. The physical fill front
     * moves through all characters continuously from left to right, karaoke-style.
     */
    private void renderKaraokeProgress(KaraokeLayoutBuilder.Layout layout, double y,
                                       float songProgress, int effectAlpha, LyricVisualStyle visualStyle) {
        int highlightColor = getCurrentLyricColor(effectAlpha);
        for (KaraokeLayoutBuilder.Segment segment : layout.segments) {
            double rawProgress = getRawKaraokeProgress(segment.word, songProgress);
            if (rawProgress <= .001) {
                continue;
            }

            double lineX = calculateAlignmentX(layout.primaryLines[segment.lineIndex], this.alignMode.getValue());
            double segmentX = lineX + segment.offsetX;
            double segmentY = y + segment.lineIndex * (getFontRenderer().getHeight() + PRIMARY_LINE_SPACING);

            /*
             * The full-screen renderer moves a single physical fill front through the
             * whole timed word. Reproduce that model in the OSD instead of completing
             * one glyph and then abruptly starting the next. This is important for a
             * token such as "我爱你": the front exits "爱" and enters "你" continuously.
             *
             * A timed word may be wrapped into several KaraokeSegments. characterOffset
             * maps each physical fragment back into the original timed word so the sweep
             * remains continuous even across an OSD line break.
             */
            double wordWidth = Math.max(.05, getFontRenderer().getStringWidthD(segment.word.word));
            double segmentWordOffset = getWordPrefixWidth(segment.word.word, segment.characterOffset);
            double paintedWidth = clampKaraokeWidth(wordWidth * rawProgress - segmentWordOffset,
                    0.0, segment.width);
            renderKaraokeSweep(segment.text, segmentX, segmentY, paintedWidth, segment.width,
                    highlightColor, visualStyle);

            // The fill is continuous in pixel space. The retained per-character emphasis
            // uses the same timed-word clock, making the scale wave follow its front.
            renderKaraokeSegmentEmphasis(segment, segmentX, segmentY, rawProgress, highlightColor, visualStyle);
        }
    }

    /**
     * Paints the sung portion with a soft feather entirely inside the real playback front.
     * The solid part preserves the completed lyric colour; the front fades smoothly from
     * left to right, matching the full-screen KTV fill rather than swapping an entire word.
     */
    private void renderKaraokeSweep(String text, double x, double y, double paintedWidth,
                                    double textWidth, int highlightColor, LyricVisualStyle visualStyle) {
        if (paintedWidth <= .05 || textWidth <= .05) {
            return;
        }
        if (paintedWidth >= textWidth - .05) {
            renderKaraokeLayer(text, x, y, 0.0, textWidth, highlightColor, .24, visualStyle);
            return;
        }

        double featherWidth = Math.min(Math.max(1.5, HudConfig.osdKaraokeTransitionWidth), paintedWidth);
        double featherLeft = Math.max(0.0, paintedWidth - featherWidth);
        if (featherLeft > .05) {
            renderKaraokeLayer(text, x, y, 0.0, featherLeft, highlightColor, .16, visualStyle);
        }

        for (int step = 0; step < KARAOKE_FEATHER_STEPS; step++) {
            double left = featherLeft + (paintedWidth - featherLeft) * step / KARAOKE_FEATHER_STEPS;
            double right = featherLeft + (paintedWidth - featherLeft) * (step + 1) / KARAOKE_FEATHER_STEPS;
            if (right <= left) {
                continue;
            }
            double distanceBehindFront = 1.0 - step / (double) KARAOKE_FEATHER_STEPS;
            double opacity = .16 + .84 * smoothStep(distanceBehindFront);
            renderKaraokeLayer(text, x, y, left, right, multiplyAlpha(highlightColor, opacity),
                    .07 + .22 * distanceBehindFront, visualStyle);
        }
    }

    /** Returns the rendered advance from the start of a timed word to a code-point offset. */
    private double getWordPrefixWidth(String text, int codePointOffset) {
        if (text == null || text.isEmpty() || codePointOffset <= 0) {
            return 0.0;
        }
        int codePointCount = text.codePointCount(0, text.length());
        int safeOffset = Math.min(codePointOffset, codePointCount);
        int end = text.offsetByCodePoints(0, safeOffset);
        return getFontRenderer().getStringWidthD(text.substring(0, end));
    }

    private double clampKaraokeWidth(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void renderKaraokeSegmentEmphasis(KaraokeLayoutBuilder.Segment segment, double x, double y,
                                              double rawProgress, int highlightColor, LyricVisualStyle visualStyle) {
        double characterTimeline = Math.max(0.0, Math.min(1.0, rawProgress))
                * segment.totalCharacterCount;
        int offset = 0;
        int characterIndex = 0;
        double characterX = x;
        while (offset < segment.text.length()) {
            int next = segment.text.offsetByCodePoints(offset, 1);
            String character = segment.text.substring(offset, next);
            double characterWidth = getFontRenderer().getStringWidthD(character);
            int globalCharacterIndex = segment.characterOffset + characterIndex;
            double characterProgress = getCharacterKaraokeProgress(characterTimeline, globalCharacterIndex);
            if (characterProgress > .001 && characterWidth > .05) {
                renderKaraokeCharacterEmphasis(character, characterX, y, characterWidth,
                        characterProgress, highlightColor, visualStyle);
            }
            characterX += characterWidth;
            offset = next;
            characterIndex++;
        }
    }

    /**
     * Matches the full-screen lyric emphasis model: every character owns a local
     * transform, so characters that have already crossed the KTV fill front remain
     * enlarged while the next character eases in. Keeping the layout coordinates
     * unchanged prevents wrapping and neighbouring glyphs from shifting.
     */
    private void renderKaraokeCharacterEmphasis(String character, double x, double y,
                                                 double width, double progress, int highlightColor,
                                                 LyricVisualStyle visualStyle) {
        if (!HudConfig.osdKaraokeEmphasisEnabled
                || HudConfig.osdKaraokePulseStrength <= .001f || width <= .05) {
            return;
        }

        double emphasis = smoothStep(progress);
        // Atlas glyphs are intentionally kept close to their native size. Quantising the
        // small retained enlargement stabilises sub-pixel sampling on the 1.8.9 HUD while
        // preserving the completed-character emphasis and its left-to-right progression.
        double requestedScale = 1.0 + Math.min(.085, HudConfig.osdKaraokePulseStrength) * emphasis;
        double scale = Math.round(requestedScale * 128.0) / 128.0;
        double centerX = x + width * .5;
        double centerY = y + getFontRenderer().getHeight() * .5;

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(scale, scale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            double paintedWidth = width * progress;
            // Clip before scaling: the feather still sweeps left-to-right, while
            // completed glyphs retain the same emphasis as full-screen lyrics.
            renderKaraokeLayer(character, x, y, 0.0, paintedWidth,
                    multiplyAlpha(highlightColor, .40 + .44 * emphasis),
                    .14 + .34 * emphasis, visualStyle);
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }
    private void renderKaraokeLayer(String text, double x, double y, double clipLeft,
                                     double clipRight, int color, double glowStrength,
                                     LyricVisualStyle visualStyle) {
        if (clipRight - clipLeft <= .05) {
            return;
        }

        /*
         * Do not use a nested stencil here. The desktop OSD is rendered into Minecraft's
         * shared HUD framebuffer, where an OptiFine/other-mod pass can leave the stencil
         * attachment or its test state unavailable. In that case the full highlighted word
         * reaches the screen and looks like an instant whole-line colour swap.
         *
         * The projected scissor follows the current HUD scale matrix and is intersected with
         * any parent scissor. Therefore it clips the glow and glyph draw to the actual moving
         * KTV fill width without changing the outer viewport clip or foreign render state.
         */
        ScissorClipManager.begin(x + clipLeft, y - 1,
                clipRight - clipLeft, getFontRenderer().getHeight() + 4);
        try {
            renderKaraokeGlow(text, x, y, color, glowStrength, visualStyle);
            bigFrString(text, x, y, color);
        } finally {
            ScissorClipManager.end();
        }
    }

    /**
     * Soft aura for the already-clipped KTV fill. The shared current-line style is
     * deliberately resolved before entering this branch, so the HUD editor's
     * current glow, bloom and radius sliders affect both the timed original text
     * and its translation. OSD-specific sliders remain a gentle KTV accent rather
     * than a separate replacement configuration path.
     */
    private void renderKaraokeGlow(String text, double x, double y, int color, double strength,
                                   LyricVisualStyle visualStyle) {
        if (!HudConfig.osdKaraokeEmphasisEnabled || strength <= .001 || RGBA.alpha(color) <= 0) {
            return;
        }

        double glowAccent = getKaraokeAccent(HudConfig.osdKaraokeGlowStrength);
        double bloomAccent = getKaraokeAccent(HudConfig.osdKaraokeBloomStrength);
        double configuredGlow = strength * visualStyle.glowStrength * glowAccent;
        double configuredBloom = strength * visualStyle.bloomStrength * bloomAccent;
        if (configuredGlow <= .001 && configuredBloom <= .001) {
            return;
        }

        double baseRadius = Math.max(.35, visualStyle.glowRadius);
        if (configuredGlow > .001) {
            double glowRadius = baseRadius * (.32 + Math.min(1.0, configuredGlow) * .68);
            drawKaraokeGlowRing(text, x, y, color, glowRadius, configuredGlow * 1.12);
        }
        if (configuredBloom > .001) {
            drawKaraokeGlowRing(text, x, y, color, baseRadius * 1.35 + .8,
                    configuredBloom * .52);
            drawKaraokeGlowRing(text, x, y, color, baseRadius * 2.15 + 1.2,
                    configuredBloom * .24);
        }
    }

    /** OSD-specific values enhance the KTV foreground without disabling shared current-line styling. */
    private double getKaraokeAccent(float configuredValue) {
        double clamped = Math.max(0.0, Math.min(1.0, configuredValue));
        return .35 + clamped * .65;
    }

    private void drawKaraokeGlowRing(String text, double x, double y, int color,
                                     double radius, double alpha) {
        int cardinalColor = multiplyAlpha(color, Math.min(1.0, alpha));
        int diagonalColor = multiplyAlpha(color, Math.min(1.0, alpha * .72));
        double diagonalRadius = radius * .72;
        getFontRenderer().drawString(text, x - radius, y, cardinalColor);
        getFontRenderer().drawString(text, x + radius, y, cardinalColor);
        getFontRenderer().drawString(text, x, y - radius, cardinalColor);
        getFontRenderer().drawString(text, x, y + radius, cardinalColor);
        getFontRenderer().drawString(text, x - diagonalRadius, y - diagonalRadius, diagonalColor);
        getFontRenderer().drawString(text, x + diagonalRadius, y - diagonalRadius, diagonalColor);
        getFontRenderer().drawString(text, x - diagonalRadius, y + diagonalRadius, diagonalColor);
        getFontRenderer().drawString(text, x + diagonalRadius, y + diagonalRadius, diagonalColor);
    }

    private int multiplyAlpha(int color, double multiplier) {
        int alpha = (int) Math.max(0, Math.min(255, Math.round(RGBA.alpha(color) * multiplier)));
        return RGBA.color(color & 0xFFFFFF, alpha);
    }

    private double smoothStep(double value) {
        return KaraokeAnimationMath.smoothStep(value);
    }

    private double getCharacterKaraokeProgress(double characterTimeline, int characterIndex) {
        return KaraokeAnimationMath.characterProgress(characterTimeline, characterIndex,
                HudConfig.osdKaraokeSmoothing);
    }

    private double getRawKaraokeProgress(LyricLine.Word word, float songProgress) {
        return word.getProgress(songProgress);
    }

    private double getKaraokeProgress(LyricLine.Word word, float songProgress) {
        return KaraokeAnimationMath.wordProgress(word, songProgress);
    }

    private void renderWrappedPrimary(String[] lines, double y, int color, boolean isCurrent,
                                      float songProgress, boolean applyGlow) {
        for (int i = 0; i < lines.length; i++) {
            renderPrimaryLine(lines[i], y + i * (getFontRenderer().getHeight() + PRIMARY_LINE_SPACING),
                    color, isCurrent, songProgress, applyGlow);
        }
    }

    private void renderWrappedSecondary(String[] lines, double y, int color, boolean isCurrent,
                                        float songProgress) {
        for (int i = 0; i < lines.length; i++) {
            renderSecondaryLine(lines[i], y + i * (getSmallFontRenderer().getHeight() + SECONDARY_LINE_SPACING),
                    color, isCurrent, songProgress);
        }
    }

    private void renderPrimaryLine(String text, double y, int color, boolean isCurrent,
                                   float songProgress, boolean applyGlow) {
        double x = calculateTextX(text, getFontRenderer(), this.alignMode.getValue());
        LyricVisualStyle visualStyle = getLyricVisualStyle(isCurrent, songProgress);
        renderScaledText(text, x, y, color, getFontRenderer(), visualStyle.scale,
                applyGlow ? visualStyle.glowStrength : 0.0,
                applyGlow ? visualStyle.bloomStrength : 0.0, visualStyle.glowRadius, false);
    }

    private void renderSecondaryLine(String text, double y, int color, boolean isCurrent,
                                     float songProgress) {
        double x = calculateTextX(text, getSmallFontRenderer(), this.alignMode.getValue());
        // The translation intentionally uses the same resolved style as the original.
        // Its smaller font remains the only visual distinction; no hidden 0.45/0.34
        // multipliers may split the HUD editor sliders into two unrelated effects.
        LyricVisualStyle visualStyle = getLyricVisualStyle(isCurrent, songProgress);
        renderScaledText(text, x, y, color, getSmallFontRenderer(), visualStyle.scale,
                visualStyle.glowStrength, visualStyle.bloomStrength, visualStyle.glowRadius, false);
    }

    /** Resolves the shared current/ordinary visual configuration for original and translated text. */
    private LyricVisualStyle getLyricVisualStyle(boolean isCurrent, float songProgress) {
        if (isCurrent) {
            double currentGlow = HudConfig.currentLyricEffectsEnabled ? HudConfig.currentGlowStrength : 0.0;
            double currentBloom = HudConfig.currentLyricEffectsEnabled ? HudConfig.currentBloomStrength : 0.0;
            return new LyricVisualStyle(getCurrentLineScale(songProgress), currentGlow, currentBloom,
                    HudConfig.currentGlowRadius);
        }
        double normalGlow = HudConfig.normalLyricEffectsEnabled ? HudConfig.normalGlowStrength : 0.0;
        double normalBloom = HudConfig.normalLyricEffectsEnabled ? HudConfig.normalBloomStrength : 0.0;
        return new LyricVisualStyle(HudConfig.normalScale, normalGlow,
                normalBloom, 1.2 + normalGlow * 1.8);
    }

    private double getCurrentLineScale(float songProgress) {
        double breathStrength = HudConfig.currentLyricEffectsEnabled ? HudConfig.currentBreathStrength : 0.0;
        double breath = Math.sin(songProgress * .0032) * breathStrength;
        return HudConfig.currentLineScale * (1.0 + breath);
    }

    private void renderScaledText(String text, double x, double y, int color, CFontRenderer font,
                                  double scale, double glowStrength, double bloomStrength,
                                  double glowRadius, boolean forceNoShadow) {
        double centerX = x + font.getStringWidthD(text) * .5;
        double centerY = y + font.getHeight() * .5;
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(scale, scale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            renderTextGlow(font, text, x, y, color, glowStrength, bloomStrength, glowRadius);
            if (!forceNoShadow && this.shadow.getValue()) {
                font.drawStringWithShadow(text, x, y, color);
            } else {
                font.drawString(text, x, y, color);
            }
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    private void renderScaledGlow(String text, double x, double y, int color, CFontRenderer font,
                                  double scale, double glowStrength, double bloomStrength,
                                  double glowRadius) {
        double centerX = x + font.getStringWidthD(text) * .5;
        double centerY = y + font.getHeight() * .5;
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(scale, scale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            renderTextGlow(font, text, x, y, color, glowStrength, bloomStrength, glowRadius);
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    /**
     * Shader-free soft glow for Forge 1.8.9. Sparse rings produce a smooth falloff
     * without allocating a framebuffer per glyph or leaking OpenGL state.
     */
    private void renderTextGlow(CFontRenderer font, String text, double x, double y, int color,
                                double glowStrength, double bloomStrength, double glowRadius) {
        if (RGBA.alpha(color) <= 0 || (glowStrength <= .001 && bloomStrength <= .001)) {
            return;
        }

        double baseRadius = Math.max(.35, glowRadius);
        if (glowStrength > .001) {
            drawGlowRing(font, text, x, y, color, .45 + baseRadius * .48, glowStrength * .32);
        }
        if (bloomStrength > .001) {
            drawGlowRing(font, text, x, y, color, baseRadius * 1.35 + .8, bloomStrength * .16);
            drawGlowRing(font, text, x, y, color, baseRadius * 2.15 + 1.2, bloomStrength * .075);
        }
    }

    private void drawGlowRing(CFontRenderer font, String text, double x, double y, int color,
                              double radius, double alpha) {
        int cardinal = multiplyAlpha(color, Math.min(1.0, alpha));
        int diagonal = multiplyAlpha(color, Math.min(1.0, alpha * .72));
        double diagonalRadius = radius * .7071;
        font.drawString(text, x - radius, y, cardinal);
        font.drawString(text, x + radius, y, cardinal);
        font.drawString(text, x, y - radius, cardinal);
        font.drawString(text, x, y + radius, cardinal);
        font.drawString(text, x - diagonalRadius, y - diagonalRadius, diagonal);
        font.drawString(text, x + diagonalRadius, y - diagonalRadius, diagonal);
        font.drawString(text, x - diagonalRadius, y + diagonalRadius, diagonal);
        font.drawString(text, x + diagonalRadius, y + diagonalRadius, diagonal);
    }

    private double calculateTextX(String text, CFontRenderer font, String alignMode) {
        if ("Right".equals(alignMode)) {
            return getContentLeft() + getContentWidth() - font.getStringWidthD(text);
        }
        if ("Center".equals(alignMode)) {
            return getContentLeft() + (getContentWidth() - font.getStringWidthD(text)) * .5;
        }
        return getContentLeft();
    }
    /**
     * The HUD editor owns lyric appearance persistence.  Resolve colours from HudConfig
     * at paint time so the current-colour picker can never mutate the ordinary lyric
     * colour through the legacy ClickGUI ColorValue instances.
     */
    private int getBaseLyricColor(int alpha) {
        return getHudConfiguredColor(HudConfig.lyricColorRgb, alpha);
    }

    /** Current colour is reserved exclusively for clipped, timed KTV foreground glyphs. */
    private int getCurrentLyricColor(int alpha) {
        return getHudConfiguredColor(HudConfig.currentLyricColorRgb, alpha);
    }

    private int getHudConfiguredColor(int configuredArgb, int requestedAlpha) {
        int configuredAlpha = (configuredArgb >>> 24) & 0xFF;
        int combinedAlpha = (int) Math.max(0, Math.min(255,
                Math.round(Math.max(0, Math.min(255, requestedAlpha)) * configuredAlpha / 255.0)));
        return RGBA.color(configuredArgb & 0x00FFFFFF, combinedAlpha);
    }

    /**
     * Resolves the timed word currently under playback. This is retained for the
     * Dynamic Island text bridge and diagnostics; OSD painting itself uses the
     * per-code-point KTV path above.
     */
    private WordInfo calculateCurrentWordInfo(LyricLine line, float songProgress) {
        WordInfo info = new WordInfo();
        if (line.words.isEmpty()) {
            return info;
        }

        for (int k = 0; k < line.words.size(); k++) {
            LyricLine.Word word = line.words.get(k);
            if (word.timestamp > songProgress) {
                info.currentIndex = Math.max(0, k - 1);
                return info;
            }
        }
        info.currentIndex = line.words.size() - 1;
        return info;
    }

    private double calculateAlignmentX(String text, String alignMode) {
        switch (alignMode) {
            case "Left":
                return getContentLeft();
            case "Center":
                return getContentLeft() + getContentWidth() * .5
                        - getFontRenderer().getStringWidthD(text) * .5;
            case "Right":
                return getContentLeft() + getContentWidth() - getFontRenderer().getStringWidthD(text);
            default:
                throw new IllegalStateException("Unexpected value: " + alignMode);
        }
    }

    /**
     * The active colour is reserved for the clipped KTV foreground layer. Rendering
     * an entire current line with it would bypass word timing and look like a hard
     * colour switch, so the unfilled base always uses the ordinary lyric colour.
     */
    private int getConfiguredLyricColor(LyricLine line, int alpha) {
        return getBaseLyricColor(alpha);
    }

    private void cleanupRender() {
        api.getGLStateManager().popMatrix();
        wpsInterface.setWidth(this.width.getValue().floatValue());
        wpsInterface.setHeight(this.height.getValue().floatValue());
    }

    /** Immutable resolved style shared by primary and translation lyric rendering. */
    private static class LyricVisualStyle {
        final double scale;
        final double glowStrength;
        final double bloomStrength;
        final double glowRadius;

        LyricVisualStyle(double scale, double glowStrength, double bloomStrength, double glowRadius) {
            this.scale = scale;
            this.glowStrength = glowStrength;
            this.bloomStrength = bloomStrength;
            this.glowRadius = glowRadius;
        }
    }

    private static class LyricRenderInfo {
        double yPosition;
        double visibilityAlpha = 1.0;
    }

    /** Cached geometry for one logical lyric row after responsive wrapping. */
    private static class LyricLayout {
        final String[] primaryLines;
        final String[] secondaryLines;
        final KaraokeLayoutBuilder.Layout karaokeLayout;
        final double primaryVisualHeight;
        final double secondaryVisualHeight;
        final double visualHeight;
        final double rowHeight;

        LyricLayout(String[] primaryLines, String[] secondaryLines, KaraokeLayoutBuilder.Layout karaokeLayout) {
            this.primaryLines = primaryLines;
            this.secondaryLines = secondaryLines;
            this.karaokeLayout = karaokeLayout;

            double primaryLineHeight = getFontRenderer().getHeight();
            this.primaryVisualHeight = primaryLines.length * primaryLineHeight
                    + Math.max(0, primaryLines.length - 1) * PRIMARY_LINE_SPACING;

            if (secondaryLines.length > 0) {
                double secondaryLineHeight = getSmallFontRenderer().getHeight();
                this.secondaryVisualHeight = secondaryLines.length * secondaryLineHeight
                        + Math.max(0, secondaryLines.length - 1) * SECONDARY_LINE_SPACING;
            } else {
                this.secondaryVisualHeight = 0.0;
            }

            this.visualHeight = primaryVisualHeight + (secondaryLines.length > 0
                    ? PRIMARY_TO_SECONDARY_SPACING + secondaryVisualHeight : 0.0);
            this.rowHeight = visualHeight + HudConfig.normalLineSpacing;
        }
    }

    private static class WordInfo {
        int currentIndex;
    }

    private static CFontRenderer getFontRenderer() {
        return FontManager.pf28bold;
    }

    private static CFontRenderer getSmallFontRenderer() {
        return FontManager.pf18bold;
    }

    private void bigFrString(String text, double x, double y, int color) {
        if (this.shadow.getValue()) {
            getFontRenderer().drawStringWithShadow(text, x, y, color);
        } else {
            getFontRenderer().drawString(text, x, y, color);
        }
    }

    private void bigFrStringCentered(String text, double x, double y, int color) {
        if (this.shadow.getValue()) {
            getFontRenderer().drawCenteredStringWithShadow(text, x, y, color);
        } else {
            getFontRenderer().drawCenteredString(text, x, y, color);
        }
    }

    private void smallFrString(String text, double x, double y, int color) {
        if (this.shadow.getValue()) {
            getSmallFontRenderer().drawStringWithShadow(text, x, y, color);
        } else {
            getSmallFontRenderer().drawString(text, x, y, color);
        }
    }

    private void smallFrStringCentered(String text, double x, double y, int color) {
        if (this.shadow.getValue()) {
            getSmallFontRenderer().drawCenteredStringWithShadow(text, x, y, color);
        } else {
            getSmallFontRenderer().drawCenteredString(text, x, y, color);
        }
    }

    private static LyricLine.Word getPrevWord(int cur, int j, LyricLine line) {
        LyricLine.Word prev;
        if (cur - 1 < 0) {
            if (j - 1 < 0) {
                prev = line.words.get(0);
            } else {
                prev = CloudMusic.lyrics.get(j - 1).words.get(CloudMusic.lyrics.get(j - 1).words.size() - 1);
            }
        } else {
            prev = line.words.get(cur - 1);
        }
        return prev;
    }

}
