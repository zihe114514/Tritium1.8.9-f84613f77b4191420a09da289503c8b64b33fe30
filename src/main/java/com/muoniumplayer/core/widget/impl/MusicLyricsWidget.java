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
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
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
            renderKaraokeCharacters(layout, y, songProgress, effectAlpha, visualStyle);
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    /**
     * 逐字渲染当前 OSD 歌词：每个字只画一遍未唱底色、一遍已唱高亮。
     *
     * <p>旧实现把三层叠在一起——「整行未唱底色（原始尺寸）」+「按填涂前沿裁剪的高亮层
     * （原始尺寸）」+「逐字放大的强调层（放大尺寸）」。同一个字形因此被画了两次且尺寸不同：
     * 放大的只有最上面那层高亮，它下面还压着一份原始尺寸的同色字形，看上去就是重影；而这个
     * 字还没唱到的部分仍由原始尺寸的底层提供，于是已唱/未唱交界处对不上，就是脱节。</p>
     *
     * <p>现在底色、高亮和裁剪矩形都在同一个逐字缩放矩阵内产生，遮罩必然跟着字形一起放大。
     * 填涂前沿仍以未放大的布局坐标推进（跨字、跨换行连续），所以放大不会引起换行抖动。</p>
     */
    private void renderKaraokeCharacters(LyricLayout layout, double y, float songProgress,
                                         int effectAlpha, LyricVisualStyle visualStyle) {
        KaraokeLayoutBuilder.Layout karaokeLayout = layout.karaokeLayout;
        // 未唱底色始终使用普通歌词色；激活色只由下面裁剪过的高亮层负责，
        // 否则整句看起来都像已经唱过了。
        int baseColor = getBaseLyricColor((int) (effectAlpha * .70f));
        int highlightColor = getCurrentLyricColor(effectAlpha);
        double lineStride = getFontRenderer().getHeight() + PRIMARY_LINE_SPACING;
        // 羽化窗口必须按 OSD 自己的字号收敛。osdKaraokeTransitionWidth 的量程(4~32)是照全屏
        // pf65bold 定的，直接套在 pf28bold 上时一个窗口就盖住整整一到两个汉字：整段已唱文本
        // 于是全部落在半透明的羽化带里、永远到不了实心色，看上去就是"KTV 染色只染了一半"。
        double featherLimit = Math.min(Math.max(1.5, HudConfig.osdKaraokeTransitionWidth),
                Math.max(1.5, getFontRenderer().getFontHeight() * .42));

        for (KaraokeLayoutBuilder.Segment segment : karaokeLayout.segments) {
            if (segment.text.isEmpty()) {
                continue;
            }

            String lineText = karaokeLayout.primaryLines[segment.lineIndex];
            double segmentX = calculateAlignmentX(lineText, this.alignMode.getValue()) + segment.offsetX;
            double segmentY = y + segment.lineIndex * lineStride;

            double rawProgress = getRawKaraokeProgress(segment.word, songProgress);
            double characterTimeline = Math.max(0.0, Math.min(1.0, rawProgress))
                    * segment.totalCharacterCount;

            /*
             * 一个 timed word 可能被拆成多个 KaraokeSegment。characterOffset 把物理片段映射
             * 回原始 timed word，因此填涂前沿跨字、跨换行都保持连续。
             */
            double wordWidth = Math.max(.05, getFontRenderer().getStringWidthD(segment.word.word));
            double segmentWordOffset = getWordPrefixWidth(segment.word.word, segment.characterOffset);
            double segmentFront = clampKaraokeWidth(wordWidth * rawProgress - segmentWordOffset,
                    0.0, segment.width);
            // 羽化窗口按整个片段的前沿计算，这样它可以跨越字与字的边界，
            // 不会因为改成逐字绘制而在每个字里各自重新淡入一次。
            double featherWidth = Math.min(featherLimit, segmentFront);

            int offset = 0;
            int characterIndex = 0;
            double prefixWidth = 0.0;
            while (offset < segment.text.length()) {
                int next = segment.text.offsetByCodePoints(offset, 1);
                String character = segment.text.substring(offset, next);
                // 用「前缀宽度之差」步进：drawString 会加入 kerning，逐字宽度直接相加会丢掉
                // 字距调整并沿行累积偏移，最终让高亮层与字形错开。
                double nextPrefixWidth = getFontRenderer().getStringWidthD(segment.text.substring(0, next));
                double characterWidth = nextPrefixWidth - prefixWidth;
                double characterProgress = getCharacterKaraokeProgress(characterTimeline,
                        segment.characterOffset + characterIndex);
                renderKaraokeCharacter(character, segmentX + prefixWidth, segmentY, characterWidth,
                        segmentFront - prefixWidth, featherWidth, characterProgress,
                        baseColor, highlightColor, visualStyle);

                prefixWidth = nextPrefixWidth;
                offset = next;
                characterIndex++;
            }
        }
    }

    /**
     * 单个字的完整绘制：一次未唱底色 + 一次裁剪过的已唱高亮，两者共用同一个逐字缩放矩阵。
     *
     * @param front        填涂前沿相对本字左边缘的位置，可能为负（还没到）或大于字宽（已唱完）
     * @param featherWidth 羽化窗口宽度，按整个片段的前沿计算，允许跨字边界
     */
    private void renderKaraokeCharacter(String character, double x, double y, double width,
                                        double front, double featherWidth, double characterProgress,
                                        int baseColor, int highlightColor,
                                        LyricVisualStyle visualStyle) {
        if (width <= .05) {
            return;
        }

        boolean emphasisEnabled = HudConfig.osdKaraokeEmphasisEnabled
                && HudConfig.osdKaraokePulseStrength > .001f;
        /*
         * 强调是"唱到这个字的一瞬间鼓一下"，而不是"唱过就一直保持放大"。
         *
         * 保持放大会让整段已唱文本长期停在一个非整数倍率上。字形图集是按 2 倍超采样、再以
         * 精确 0.5 倍缩小来保证锐利的，任何额外的非整数倍率都会重新采样成一团发虚的墨；而且
         * 每个字是绕自身中心放大的，放大后必然压到相邻字上，于是既模糊又带重影。改成脉冲后
         * 只有正在唱的那一个字被放大，唱完立刻回到 1.0 倍恢复锐利。
         */
        double emphasis = emphasisEnabled
                ? Math.sin(Math.PI * smoothStep(characterProgress))
                : 0.0;
        // 这一点放大量量化到 1/128，可以稳定 1.8.9 HUD 上的次像素采样。
        double requestedScale = 1.0
                + Math.min(.085, Math.max(0.0, HudConfig.osdKaraokePulseStrength)) * emphasis;
        double characterScale = Math.round(requestedScale * 128.0) / 128.0;
        boolean scaled = characterScale > 1.0005;

        if (scaled) {
            double centerX = x + width * .5;
            // 用 getFontHeight() 而不是取整过的 getHeight()：锚点偏离字形真实中心会让
            // 放大后的字形与裁剪框在纵向上错开半个像素。
            double centerY = y + getFontRenderer().getFontHeight() * .5;
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().scale(characterScale, characterScale, 1);
            api.getGLStateManager().translate(-centerX, -centerY, 0);
        }
        try {
            bigFrString(character, x, y, baseColor);
            renderKaraokeSweep(character, x, y, front, featherWidth, width,
                    highlightColor, visualStyle, emphasis);
        } finally {
            if (scaled) {
                api.getGLStateManager().popMatrix();
            }
        }
    }

    /**
     * 把本字已唱的部分画成实心 + 前沿羽化。羽化完全位于真实播放前沿的内侧，
     * 并且窗口是按整个片段算出来的，所以前沿的软边可以横跨字与字的边界。
     */
    private void renderKaraokeSweep(String text, double x, double y, double front,
                                    double featherWidth, double textWidth,
                                    int highlightColor, LyricVisualStyle visualStyle,
                                    double emphasis) {
        double paintedWidth = Math.min(front, textWidth);
        if (paintedWidth <= .05 || textWidth <= .05) {
            return;
        }

        // 光晕整段只画一次，裁剪到真实已唱区域。以前每条羽化带都自带一圈光晕(8 次偏移绘制)，
        // 十条就是八十次同字形叠加，前沿必然过曝糊成一团。
        renderKaraokeGlowLayer(text, x, y, 0.0, paintedWidth, highlightColor,
                .18 + .30 * emphasis, visualStyle);

        double solidRight = KaraokeAnimationMath.solidRight(front, featherWidth, textWidth);
        if (solidRight > .05) {
            renderKaraokeLayer(text, x, y, 0.0, solidRight, highlightColor);
        }
        if (featherWidth <= .01) {
            return;
        }

        // 羽化带条数按它在屏幕上真正占几个像素来定，避免亚像素窄条被取整撑开后互相重叠。
        int steps = KaraokeAnimationMath.featherSteps(featherWidth,
                karaokeScreenScale() * visualStyle.scale, KARAOKE_FEATHER_STEPS);
        double[] strip = new double[2];
        for (int step = 0; step < steps; step++) {
            if (!KaraokeAnimationMath.featherStrip(front, featherWidth, textWidth,
                    step, steps, strip)) {
                continue;
            }
            double distanceBehindFront = 1.0 - step / (double) steps;
            double opacity = .16 + .84 * smoothStep(distanceBehindFront);
            renderKaraokeLayer(text, x, y, strip[0], strip[1],
                    multiplyAlpha(highlightColor, opacity));
        }
    }

    /** 一个逻辑单位在屏幕上对应几个像素：GUI 缩放 × 本 HUD 的缩放。 */
    private double karaokeScreenScale() {
        double hudScale = editorPreviewActive ? editorPreviewScale : HudConfig.lyricScale;
        return Math.max(.5, RenderSystem.getScaleFactor() * Math.max(.1, hudScale));
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

    /**
     * 已唱高亮层的纵向裁剪范围。
     *
     * <p>{@code CFontRenderer.drawString} 内部先 {@code y -= 2} 再 {@code scale(0.5)}，因此一个
     * 字形实际占据 {@code [y - 2, y - 2 + fontHeight * 0.5]}，也就是
     * {@code [y - 2, y + getFontHeight() + 2.25]}。旧的裁剪框是
     * {@code [y - 1, y - 1 + getHeight() + 4]}——上边界比墨迹顶端低了一个逻辑像素，而且行高还被
     * {@code getHeight()} 向下取整过。结果是已唱高亮层的顶部被削平、未唱底色层却是完整的，
     * 两层叠在一起就表现为"KTV 染色显示不全"。这里按真实墨迹盒给出上下各约一像素的余量。</p>
     */
    private static final double KARAOKE_CLIP_TOP_OFFSET = -3.0;

    private double karaokeClipHeight() {
        return getFontRenderer().getFontHeight() + 7.0;
    }

    /** 只画光晕的一层，裁剪到给定区间。 */
    private void renderKaraokeGlowLayer(String text, double x, double y, double clipLeft,
                                        double clipRight, int color, double glowStrength,
                                        LyricVisualStyle visualStyle) {
        if (clipRight - clipLeft <= .05 || glowStrength <= .001) {
            return;
        }

        ScissorClipManager.begin(x + clipLeft, y + KARAOKE_CLIP_TOP_OFFSET,
                clipRight - clipLeft, karaokeClipHeight());
        try {
            renderKaraokeGlow(text, x, y, color, glowStrength, visualStyle);
        } finally {
            ScissorClipManager.end();
        }
    }

    private void renderKaraokeLayer(String text, double x, double y, double clipLeft,
                                     double clipRight, int color) {
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
        ScissorClipManager.begin(x + clipLeft, y + KARAOKE_CLIP_TOP_OFFSET,
                clipRight - clipLeft, karaokeClipHeight());
        try {
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
