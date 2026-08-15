package tritium.widget.impl;

import today.opai.api.enums.EnumChatColor;
import today.opai.api.enums.EnumModuleCategory;
import today.opai.api.features.ExtensionModule;
import today.opai.api.features.ExtensionWidget;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.modules.values.BooleanValue;
import today.opai.api.interfaces.modules.values.ColorValue;
import today.opai.api.interfaces.modules.values.ModeValue;
import today.opai.api.interfaces.modules.values.NumberValue;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.reflection.Reflection;
import tritium.rendering.RGBA;
import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Easing;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.font.CFontRenderer;
import tritium.screens.ncm.LyricLine;
import tritium.settings.ClientSettings;
import tritium.settings.HudConfig;
import tritium.utils.Tuple;
import tritium.utils.WidgetWrapper;
import tritium.utils.math.Mth;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * @author IzumiiKonata
 * Date: 2025/2/14 20:34
 */
public class MusicLyricsWidget extends ExtensionModule implements SharedConstants, SharedRenderingConstants, EventHandler {
    public ModeValue scrollEffects = api.getValueManager().createModes("Scroll Effects", "KTV", new String[] { "KTV", "Scroll", "FadeIn", "SlideIn" });
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

    public ExtensionWidget widget;
    WidgetWrapper.WidgetPosSizeInterface wpsInterface;
    
    public MusicLyricsWidget() {
        super("Music Lyrics", "Show lyrics.", EnumModuleCategory.VISUAL);

        graceScroll.setHiddenPredicate(() -> singleLine.getValue());
        showRoman.setHiddenPredicate(() -> !showTranslation.getValue());
        dynIsland.setHiddenPredicate(() -> !Reflection.DYNAMIC_ISLAND_SUPPORTED);
        
        this.addValues(scrollEffects, alignMode, width, height, lyricHeight, lyricColor, currentLyricColor, shadow, singleLine, graceScroll, showRoman, dynIsland);

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

    private static final double CONTENT_HORIZONTAL_PADDING = 12.0;
    private static final double PRIMARY_LINE_SPACING = 3.0;
    private static final double SECONDARY_LINE_SPACING = 2.0;
    private static final double PRIMARY_TO_SECONDARY_SPACING = 5.0;
    private static final double ROW_SPACING = 10.0;
    private static final double KARAOKE_GLOW_PRE_ROLL_MS = 110.0;
    private static final double KARAOKE_GLOW_AFTER_ROLL_MS = 165.0;
    private static final int KARAOKE_FEATHER_STEPS = 10;
    private static final int KARAOKE_HEAD_STEPS = 12;

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
        float hudScale = HudConfig.lyricScale;
        float baseW = this.width.getValue().floatValue();
        float baseH = this.height.getValue().floatValue();
        float hudX = (float) (HudConfig.lyricX * (getWidth() - baseW * hudScale));
        float hudY = (float) (HudConfig.lyricY * (getHeight() - baseH * hudScale));
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

            LyricLine currentLine = CloudMusic.lyrics.get(currentIndex);
            LyricLayout currentLayout = createLyricLayout(currentLine);
            double viewportTop = wpsInterface.getY();
            double viewportBottom = viewportTop + wpsInterface.getHeight();
            // Keep the active lyric block centered. This is especially important when
            // a narrow HUD wraps it into multiple physical text rows.
            double currentTop = viewportTop + (wpsInterface.getHeight() - currentLayout.visualHeight) * .5;

            if (singleLineMode) {
                renderLyricLine(currentLine, currentIndex, currentIndex, currentTop,
                        currentLayout, songProgress);
                return;
            }

            // The viewport height, not a fixed number setting, decides how many rows are
            // visible. Lines just outside the clip are kept in the layout pass so their
            // motion and edge fade remain continuous while scrolling.
            double fadePadding = getEdgeFadeSize();
            double y = currentTop;
            for (int i = currentIndex - 1; i >= 0; i--) {
                LyricLayout layout = createLyricLayout(CloudMusic.lyrics.get(i));
                y -= layout.rowHeight;
                if (y + layout.visualHeight < viewportTop - fadePadding) {
                    break;
                }
                renderLyricLine(CloudMusic.lyrics.get(i), i, currentIndex, y, layout, songProgress);
            }

            renderLyricLine(currentLine, currentIndex, currentIndex, currentTop,
                    currentLayout, songProgress);

            y = currentTop + currentLayout.rowHeight;
            for (int i = currentIndex + 1; i < CloudMusic.lyrics.size(); i++) {
                if (y > viewportBottom + fadePadding) {
                    break;
                }
                LyricLine line = CloudMusic.lyrics.get(i);
                LyricLayout layout = createLyricLayout(line);
                renderLyricLine(line, i, currentIndex, y, layout, songProgress);
                y += layout.rowHeight;
            }
        }
    }

    private void renderLyricLine(LyricLine line, int index, int currentIndex,
                                 double targetY, LyricLayout layout, float songProgress) {
        double animatedY = updateLineY(line, targetY);
        double edgeAlpha = calculateEdgeAlpha(animatedY, layout.visualHeight);
        if (edgeAlpha <= .01) {
            return;
        }

        LyricRenderInfo renderInfo = new LyricRenderInfo();
        renderInfo.yPosition = animatedY;
        renderInfo.visibilityAlpha = edgeAlpha;
        updateLyricAnimation(line, index == currentIndex, edgeAlpha);
        renderLyricText(line, renderInfo, index, currentIndex, layout, songProgress);
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
        KaraokeLayout karaokeLayout = line.words.isEmpty() ? null : createKaraokeLayout(line);
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
    private KaraokeLayout createKaraokeLayout(LyricLine line) {
        List<String> physicalLines = new ArrayList<>();
        List<KaraokeSegment> segments = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        double currentWidth = 0.0;
        double availableWidth = getContentWidth();

        for (LyricLine.Word word : line.words) {
            String text = word.word;
            if (text == null || text.isEmpty()) {
                continue;
            }

            double wordWidth = getFontRenderer().getStringWidthD(text);
            if (wordWidth <= availableWidth) {
                if (currentLine.length() > 0 && currentWidth + wordWidth > availableWidth) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }
                segments.add(new KaraokeSegment(text, word, physicalLines.size(), currentWidth, wordWidth));
                currentLine.append(text);
                currentWidth += wordWidth;
                continue;
            }

            int start = 0;
            while (start < text.length()) {
                if (currentLine.length() > 0 && currentWidth >= availableWidth - .01) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }

                int end = findKaraokeFragmentEnd(text, start, availableWidth - currentWidth);
                if (end <= start) {
                    if (currentLine.length() > 0) {
                        physicalLines.add(currentLine.toString());
                        currentLine.setLength(0);
                        currentWidth = 0.0;
                        continue;
                    }
                    end = text.offsetByCodePoints(start, 1);
                }

                String fragment = text.substring(start, end);
                double fragmentWidth = getFontRenderer().getStringWidthD(fragment);
                segments.add(new KaraokeSegment(fragment, word, physicalLines.size(), currentWidth, fragmentWidth));
                currentLine.append(fragment);
                currentWidth += fragmentWidth;
                start = end;

                if (start < text.length()) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }
            }
        }

        if (currentLine.length() > 0 || physicalLines.isEmpty()) {
            physicalLines.add(currentLine.toString());
        }
        return new KaraokeLayout(physicalLines.toArray(new String[0]), segments);
    }

    private int findKaraokeFragmentEnd(String text, int start, double availableWidth) {
        int end = start;
        int candidate = start;
        while (candidate < text.length()) {
            int next = text.offsetByCodePoints(candidate, 1);
            if (getFontRenderer().getStringWidthD(text.substring(start, next)) > availableWidth) {
                break;
            }
            end = next;
            candidate = next;
        }
        return end;
    }

    private String[] fitText(CFontRenderer font, String text) {
        if (text == null || text.isEmpty()) {
            return new String[]{""};
        }
        String[] fitted = font.fitWidth(text, getContentWidth());
        return fitted == null || fitted.length == 0 ? new String[]{text} : fitted;
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
                                 int index, int currentIndex, LyricLayout layout,
                                 float songProgress) {
        boolean isCurrent = index == currentIndex;
        boolean hasWords = !line.words.isEmpty();
        boolean useKaraoke = isCurrent && layout.karaokeLayout != null
                && "KTV".equals(this.scrollEffects.getValue());
        boolean canAnimateWords = isCurrent && hasWords && layout.primaryLines.length == 1 && !useKaraoke;
        boolean slideIn = canAnimateWords && this.scrollEffects.getValue().equals("SlideIn")
                && !this.alignMode.getValue().equals("Left");
        int effectAlpha = (int) (255 * line.lineAlpha * renderInfo.visibilityAlpha);

        if (useKaraoke) {
            // Base text, glow, stencil clips and moving highlight must share one transform.
            // Otherwise current-line scaling makes the coloured layer drift away from the glyphs.
            renderKaraokeBlock(layout, renderInfo.yPosition, songProgress, effectAlpha);
        } else {
            int primaryAlpha = (int) ((isCurrent && hasWords ? 80 : 255)
                    * line.lineAlpha * renderInfo.visibilityAlpha);
            if (!slideIn) {
                renderWrappedPrimary(layout.primaryLines, renderInfo.yPosition,
                        getConfiguredLyricColor(line, primaryAlpha), isCurrent, songProgress, true);
            }
        }

        if (layout.secondaryLines.length > 0) {
            int secondaryAlpha = (int) (255 * line.lineAlpha * renderInfo.visibilityAlpha
                    * HudConfig.secondaryOpacity);
            double secondaryY = renderInfo.yPosition + layout.primaryVisualHeight
                    + PRIMARY_TO_SECONDARY_SPACING;
            renderWrappedSecondary(layout.secondaryLines, secondaryY,
                    getConfiguredLyricColor(line, secondaryAlpha), isCurrent, songProgress);
        }

        if (!useKaraoke && canAnimateWords) {
            // The legacy effects require one uninterrupted baseline. Wrapped lyrics
            // retain their responsive layout unless KTV mode is selected.
            handleScrollEffects(line, renderInfo, songProgress, effectAlpha);
        }
    }

    private void renderKaraokeBlock(LyricLayout layout, double y,
                                     float songProgress, int effectAlpha) {
        double centerX = getContentLeft() + getContentWidth() * .5;
        double centerY = y + layout.primaryVisualHeight * .5;
        double scale = getCurrentLineScale(songProgress);

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(scale, scale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            renderKaraokeAmbientGlow(layout.karaokeLayout, y, effectAlpha);
            renderWrappedPrimaryUnscaled(layout.primaryLines, y,
                    getBaseLyricColor((int) (effectAlpha * .70f)));
            renderKaraokeProgress(layout.karaokeLayout, y, songProgress, effectAlpha);
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
     * Renders the active KTV layer as a soft, travelling light band instead of replacing
     * the unsung text with one hard clipping edge. The completed portion stays crisp while
     * the front edge has several translucent layers and a restrained halo.
     */
    private void renderKaraokeProgress(KaraokeLayout layout, double y,
                                       float songProgress, int effectAlpha) {
        for (KaraokeSegment segment : layout.segments) {
            double rawProgress = getRawKaraokeProgress(segment.word, songProgress);
            double progress = smoothStep(Math.max(0.0, Math.min(1.0, rawProgress)));

            String physicalLine = layout.primaryLines[segment.lineIndex];
            double lineX = calculateAlignmentX(physicalLine, this.alignMode.getValue());
            double x = lineX + segment.offsetX;
            double segmentY = y + segment.lineIndex * (getFontRenderer().getHeight() + PRIMARY_LINE_SPACING);
            int highlightColor = getCurrentLyricColor(effectAlpha);

            if (progress > .002) {
                if (progress >= .998) {
                    // The whole sung area keeps a visible aura instead of only the moving head glowing.
                    renderKaraokeLayer(segment.text, x, segmentY, 0, segment.width,
                            highlightColor, .24);
                } else {
                    double front = segment.width * progress;
                    double featherWidth = Math.min(Math.max(4.0, HudConfig.currentTransitionWidth),
                            Math.max(4.0, segment.width * .72));
                    double solidRight = Math.max(0.0, front - featherWidth * .72);

                    renderKaraokeLayer(segment.text, x, segmentY, 0, solidRight,
                            highlightColor, .20);

                    // More and narrower strips make the colour bloom across partial glyphs
                    // instead of changing one character at a time.
                    for (int step = 0; step < KARAOKE_FEATHER_STEPS; step++) {
                        double left = solidRight + featherWidth * step / KARAOKE_FEATHER_STEPS;
                        double right = Math.min(segment.width,
                                solidRight + featherWidth * (step + 1) / KARAOKE_FEATHER_STEPS);
                        if (right <= left) {
                            continue;
                        }

                        double t = 1.0 - step / (double) KARAOKE_FEATHER_STEPS;
                        double opacity = .14 + .82 * smoothStep(t);
                        renderKaraokeLayer(segment.text, x, segmentY, left, right,
                                multiplyAlpha(highlightColor, opacity), .07 + .25 * t);
                    }
                }
            }

            // Render the light head against the complete physical line rather than against
            // one timed word. Its halo can therefore overlap the previous and next glyph,
            // producing a continuous travelling light instead of a per-character flash.
            renderKaraokeTransitionGlow(physicalLine, lineX, segmentY, segment,
                    rawProgress, songProgress, highlightColor);
            renderKaraokeWordPulse(segment, x, segmentY, rawProgress, highlightColor);
        }
    }

    private void renderKaraokeAmbientGlow(KaraokeLayout layout, double y, int effectAlpha) {
        int ambientColor = getCurrentLyricColor((int) (effectAlpha * .52));
        for (int lineIndex = 0; lineIndex < layout.primaryLines.length; lineIndex++) {
            String text = layout.primaryLines[lineIndex];
            double x = calculateAlignmentX(text, this.alignMode.getValue());
            double lineY = y + lineIndex * (getFontRenderer().getHeight() + PRIMARY_LINE_SPACING);
            renderTextGlow(getFontRenderer(), text, x, lineY, ambientColor,
                    HudConfig.currentGlowStrength * .44,
                    HudConfig.currentBloomStrength, HudConfig.currentGlowRadius);
        }
    }

    /**
     * Layout-neutral KTV pulse. Only the already-painted portion is redrawn around the
     * segment centre; wrapping and neighbouring glyph positions therefore never jump.
     */
    private void renderKaraokeWordPulse(KaraokeSegment segment, double x, double y,
                                        double rawProgress, int highlightColor) {
        if (HudConfig.currentWordScale <= .001f || rawProgress <= 0.0 || rawProgress >= 1.0) {
            return;
        }
        double progress = Math.max(0.0, Math.min(1.0, rawProgress));
        double pulse = Math.sin(Math.PI * progress);
        double scale = 1.0 + HudConfig.currentWordScale * pulse;
        double centerX = x + segment.width * .5;
        double centerY = y + getFontRenderer().getHeight() * .5;

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(scale, scale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            double paintedWidth = segment.width * smoothStep(progress);
            renderKaraokeLayer(segment.text, x, y, 0.0, paintedWidth,
                    multiplyAlpha(highlightColor, .38 + .36 * pulse),
                    .18 + .28 * pulse);
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    private void renderKaraokeTransitionGlow(String physicalLine, double lineX, double lineY,
                                              KaraokeSegment segment, double rawProgress,
                                              float songProgress, int highlightColor) {
        double wordStart = segment.word.timestamp;
        double wordEnd = wordStart + Math.max(1L, segment.word.duration);
        if (songProgress < wordStart - KARAOKE_GLOW_PRE_ROLL_MS
                || songProgress > wordEnd + KARAOKE_GLOW_AFTER_ROLL_MS) {
            return;
        }

        double envelope = 1.0;
        if (songProgress < wordStart) {
            envelope = smoothStep((songProgress - (wordStart - KARAOKE_GLOW_PRE_ROLL_MS))
                    / KARAOKE_GLOW_PRE_ROLL_MS);
        } else if (songProgress > wordEnd) {
            envelope = 1.0 - smoothStep((songProgress - wordEnd) / KARAOKE_GLOW_AFTER_ROLL_MS);
        }
        if (envelope <= .002) {
            return;
        }

        double progress = smoothStep(Math.max(0.0, Math.min(1.0, rawProgress)));
        double center = segment.offsetX + segment.width * progress;
        double halfBand = Math.max(4.0, HudConfig.currentTransitionWidth);
        double lineWidth = getFontRenderer().getStringWidthD(physicalLine);

        for (int step = 0; step < KARAOKE_HEAD_STEPS; step++) {
            double left = center - halfBand + halfBand * 2.0 * step / KARAOKE_HEAD_STEPS;
            double right = center - halfBand + halfBand * 2.0 * (step + 1) / KARAOKE_HEAD_STEPS;
            left = Math.max(0.0, left);
            right = Math.min(lineWidth, right);
            if (right <= left) {
                continue;
            }

            double stripCenter = (left + right) * .5;
            double distance = Math.abs(stripCenter - center) / halfBand;
            double intensity = smoothStep(1.0 - Math.min(1.0, distance));
            double opacity = envelope * (.12 + .68 * intensity);
            double glowStrength = envelope * (.20 + .48 * intensity);
            renderKaraokeLayer(physicalLine, lineX, lineY, left, right,
                    multiplyAlpha(highlightColor, opacity), glowStrength);
        }
    }

    private void renderKaraokeLayer(String text, double x, double y, double clipLeft,
                                    double clipRight, int color, double glowStrength) {
        if (clipRight - clipLeft <= .05) {
            return;
        }

        StencilClipManager.beginClip(() -> Rect.draw(x + clipLeft, y - 1,
                clipRight - clipLeft, getFontRenderer().getHeight() + 4, -1));
        renderKaraokeGlow(text, x, y, color, glowStrength);
        bigFrString(text, x, y, color);
        StencilClipManager.endClip();
    }

    /** Soft eight-direction aura shared by the complete lyric and the travelling KTV head. */
    private void renderKaraokeGlow(String text, double x, double y, int color, double strength) {
        if (strength <= .001 || RGBA.alpha(color) <= 0) {
            return;
        }

        double configuredStrength = strength * HudConfig.currentGlowStrength;
        int cardinalColor = multiplyAlpha(color, Math.min(1.0, configuredStrength * 1.12));
        int diagonalColor = multiplyAlpha(color, Math.min(1.0, configuredStrength * .78));
        double radius = Math.max(.35, HudConfig.currentGlowRadius * (.32 + configuredStrength * .68));
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
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    private double getRawKaraokeProgress(LyricLine.Word word, float songProgress) {
        if (word.duration <= 0L) {
            return songProgress >= word.timestamp ? 1.0 : 0.0;
        }
        return (songProgress - word.timestamp) / (double) word.duration;
    }

    private double getKaraokeProgress(LyricLine.Word word, float songProgress) {
        return smoothStep(Mth.limit(getRawKaraokeProgress(word, songProgress), 0.0, 1.0));
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
        double scale = isCurrent ? getCurrentLineScale(songProgress) : HudConfig.normalScale;
        double glow = applyGlow ? (isCurrent ? HudConfig.currentGlowStrength : HudConfig.normalGlowStrength) : 0.0;
        double bloom = applyGlow ? (isCurrent ? HudConfig.currentBloomStrength : HudConfig.normalBloomStrength) : 0.0;
        double radius = isCurrent ? HudConfig.currentGlowRadius : 1.2 + HudConfig.normalGlowStrength * 1.8;
        renderScaledText(text, x, y, color, getFontRenderer(), scale, glow, bloom, radius, false);
    }

    private void renderSecondaryLine(String text, double y, int color, boolean isCurrent,
                                     float songProgress) {
        double x = calculateTextX(text, getSmallFontRenderer(), this.alignMode.getValue());
        double scale = isCurrent ? getCurrentLineScale(songProgress) : HudConfig.normalScale;
        double glow = (isCurrent ? HudConfig.currentGlowStrength : HudConfig.normalGlowStrength) * .45;
        double bloom = (isCurrent ? HudConfig.currentBloomStrength : HudConfig.normalBloomStrength) * .34;
        double radius = isCurrent ? HudConfig.currentGlowRadius * .72 : 1.0 + HudConfig.normalGlowStrength;
        renderScaledText(text, x, y, color, getSmallFontRenderer(), scale, glow, bloom, radius, false);
    }

    private double getCurrentLineScale(float songProgress) {
        double breath = Math.sin(songProgress * .0032) * HudConfig.currentBreathStrength;
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
    private int getBaseLyricColor(int alpha) {
        Color baseColor = lyricColor.getValue();
        return RGBA.color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha);
    }

    private int getCurrentLyricColor(int alpha) {
        Color activeColor = currentLyricColor.getValue();
        return RGBA.color(activeColor.getRed(), activeColor.getGreen(), activeColor.getBlue(), alpha);
    }

    private void handleScrollEffects(LyricLine line, LyricRenderInfo renderInfo, float songProgress, int effectAlpha) {
        WordInfo wordInfo = calculateCurrentWordInfo(line, songProgress);

        updateScrollWidth(line, wordInfo, songProgress);

        renderScrollEffect(line, renderInfo, wordInfo, songProgress, effectAlpha);
    }

    private WordInfo calculateCurrentWordInfo(LyricLine line, float songProgress) {
        WordInfo info = new WordInfo();

        // find current word index
        for (int k = 0; k < line.words.size(); k++) {
            LyricLine.Word word = line.words.get(k);

            if (word.timestamp > songProgress) {
                info.currentIndex = Math.max(0, k - 1);
                break;
            } else if (k == line.words.size() - 1) {
                info.currentIndex = k;
            }
        }

        // calculate text before current word
        for (int m = 0; m < info.currentIndex; m++) {
            info.textBefore.append(line.words.get(m).word);
        }

        // calculate accumulated text
        for (int m = 0; m < info.currentIndex + 1; m++) {
            info.textAccumulated.append(line.words.get(m).word);
        }

        return info;
    }

    private void updateScrollWidth(LyricLine line, WordInfo wordInfo, float songProgress) {
        LyricLine.Word current = line.words.get(wordInfo.currentIndex);

        double value = (songProgress - current.timestamp) / (double) (current.duration);

        double progress = Mth.limit(value, 0, 1);

        double offsetX = progress * getFontRenderer().getStringWidthD(current.word);

        line.scrollWidth = getFontRenderer().getStringWidthD(wordInfo.textBefore.toString()) + offsetX;
    }

    private void renderScrollEffect(LyricLine line, LyricRenderInfo renderInfo, WordInfo wordInfo, float songProgress, int effectAlpha) {
        String effectMode = this.scrollEffects.getValue();

        switch (effectMode) {
            case "Scroll":
                renderScrollMode(line, renderInfo, effectAlpha);
                break;
            case "FadeIn":
                renderFadeInMode(line, renderInfo, wordInfo, songProgress, effectAlpha);
                break;
            case "SlideIn":
                renderSlideInMode(line, renderInfo, wordInfo, songProgress, effectAlpha);
                break;
        }
    }

    private void renderScrollMode(LyricLine line, LyricRenderInfo renderInfo, int effectAlpha) {
        String alignMode = this.alignMode.getValue();
        double x = calculateAlignmentX(line.getLyric(), alignMode);

        StencilClipManager.beginClip(() -> Rect.draw(x, renderInfo.yPosition, line.scrollWidth + 1, getFontRenderer().getHeight() + 4, -1));

        renderAlignedText(line.getLyric(), renderInfo.yPosition, getConfiguredLyricColor(line, effectAlpha), alignMode);

        StencilClipManager.endClip();
    }

    private void renderFadeInMode(LyricLine line, LyricRenderInfo renderInfo, WordInfo wordInfo, float songProgress, int effectAlpha) {
        String alignMode = this.alignMode.getValue();

        double offsetX = calculateAlignmentX(line.getLyric(), alignMode);
        for (int m = 0; m < wordInfo.currentIndex + 1; m++) {
            LyricLine.Word word = line.words.get(m);
            String wordText = word.word;

            if (m == wordInfo.currentIndex) {
                updateCurrentWordAnimation(word, line, wordInfo.currentIndex, songProgress);
            } else if (m < wordInfo.currentIndex) {
                word.alpha = 1;
            }

            double stWidth = getFontRenderer().getStringWidthD(wordText);
            bigFrString(wordText, offsetX, renderInfo.yPosition,
                    getConfiguredLyricColor(line, (int) (word.alpha * effectAlpha)));

            offsetX += stWidth;
        }
    }

    private void renderSlideInMode(LyricLine line, LyricRenderInfo renderInfo, WordInfo wordInfo, float songProgress, int effectAlpha) {
        String alignMode = this.alignMode.getValue();

        double targetX = calculateSlideInTargetX(line, alignMode);

        Runnable renderTask = () -> {
            double offsetX = targetX;
            double targetOffsetX = 0;

            for (int m = 0; m < wordInfo.currentIndex + 1; m++) {
                LyricLine.Word word = line.words.get(m);
                String wordText = word.word;
                double stWidth = getFontRenderer().getStringWidthD(wordText);

                if (m == wordInfo.currentIndex) {
                    updateCurrentWordAnimation(word, line, wordInfo.currentIndex, songProgress);

                    Easing easeInOutQuad = Easing.EASE_OUT_CUBIC;
                    targetOffsetX += stWidth * easeInOutQuad.getFunction().apply(word.progress);
                } else if (m < wordInfo.currentIndex) {
                    word.alpha = 1;
                    targetOffsetX += stWidth;
                }

                bigFrString(wordText, offsetX, renderInfo.yPosition,
                        getConfiguredLyricColor(line, (int) (word.alpha * effectAlpha)));

                offsetX += stWidth;
            }

            line.targetOffsetX = targetOffsetX;
        };

        renderTask.run();
    }

    private void updateCurrentWordAnimation(LyricLine.Word word, LyricLine line,
                                            int currentIndex, float songProgress) {
        double perc = Mth.limit((songProgress - word.timestamp) / (double) (word.duration), 0, 1);
        double clamped = Math.max(0, Math.min(1, perc));

        word.progress = Interpolations.interpolate(word.progress, clamped, 1);
        word.alpha = (float) Math.min(1, clamped * 1.25f);
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

    private double calculateSlideInTargetX(LyricLine line, String alignMode) {
        switch (alignMode) {
            case "Left":
                return getContentLeft();
            case "Center":
                return getContentLeft() + getContentWidth() * .5 - line.targetOffsetX * .5;
            case "Right":
                return getContentLeft() + getContentWidth() - line.targetOffsetX;
            default:
                throw new IllegalStateException("Unexpected value: " + alignMode);
        }
    }

    private int getConfiguredLyricColor(LyricLine line, int alpha) {
        Color baseColor = line == CloudMusic.currentLyric ? currentLyricColor.getValue() : lyricColor.getValue();
        return RGBA.color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha);
    }

    private void renderAlignedText(String text, double y, int color, String alignMode) {
        bigFrString(text, calculateAlignmentX(text, alignMode), y, color);
    }

    private void cleanupRender() {
        api.getGLStateManager().popMatrix();
        wpsInterface.setWidth(this.width.getValue().floatValue());
        wpsInterface.setHeight(this.height.getValue().floatValue());
    }

    private static class LyricRenderInfo {
        double yPosition;
        double visibilityAlpha = 1.0;
    }

    /** Cached geometry for one logical lyric row after responsive wrapping. */
    private static class LyricLayout {
        final String[] primaryLines;
        final String[] secondaryLines;
        final KaraokeLayout karaokeLayout;
        final double primaryVisualHeight;
        final double secondaryVisualHeight;
        final double visualHeight;
        final double rowHeight;

        LyricLayout(String[] primaryLines, String[] secondaryLines, KaraokeLayout karaokeLayout) {
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

    private static class KaraokeLayout {
        final String[] primaryLines;
        final List<KaraokeSegment> segments;

        KaraokeLayout(String[] primaryLines, List<KaraokeSegment> segments) {
            this.primaryLines = primaryLines;
            this.segments = segments;
        }
    }

    private static class KaraokeSegment {
        final String text;
        final LyricLine.Word word;
        final int lineIndex;
        final double offsetX;
        final double width;

        KaraokeSegment(String text, LyricLine.Word word, int lineIndex, double offsetX, double width) {
            this.text = text;
            this.word = word;
            this.lineIndex = lineIndex;
            this.offsetX = offsetX;
            this.width = width;
        }
    }

    private static class WordInfo {
        int currentIndex = 0;
        StringBuilder textBefore = new StringBuilder();
        StringBuilder textAccumulated = new StringBuilder();
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



