package tritium.screens.ncm;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import tritium.MuoniumPlayerExtension;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.rendering.*;
import tritium.rendering.Image;
import tritium.rendering.animation.Easing;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.entities.impl.ScrollText;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.ShaderProgram;
import tritium.rendering.shader.Shaders;
import tritium.rendering.texture.ITextureObject;
import tritium.rendering.ui.widgets.IconWidget;
import tritium.settings.ClientSettings;
import tritium.settings.HudConfig;
import tritium.utils.Location;
import tritium.utils.cursor.CursorUtils;
import tritium.utils.math.Mth;
import tritium.utils.network.HttpUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;
import tritium.utils.timing.Timer;
import tritium.widget.impl.MusicLyricsWidget;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author IzumiiKonata
 * Date: 2025/10/17 21:56
 */
public class MusicLyricsPanel implements SharedRenderingConstants, SharedConstants {
    static double scrollOffset, scrollTarget;

    float musicBgAlpha = 1.0f;
    static ITextureObject prevBg = null, prevCover;
    static Music prevMusic = null;

    float alpha = 0f;
    boolean closing = false;

    Framebuffer baseFb, stencilFb;

    Timer scrollOffsetResetTimer = new Timer();

    double coverSize = (CloudMusic.player == null || CloudMusic.player.isPausing()) ? this.getCoverSizeMin() : this.getCoverSizeMax();
    float coverAlpha = 1f;

    boolean progressBarDragging = false;
    double progressBarProgressOverride = 0;
    double progressBarHeight = 8, volumeBarHeight = 8;
    private final VolumeControl volumeControl = new VolumeControl();

    boolean prevMouse = false;

    ScrollText stMusicName = new ScrollText(), stArtists = new ScrollText();
    IconWidget playPauseButton = new IconWidget("G", FontManager.music40, 0, 0, 24, 24);
    IconWidget prev = new IconWidget("E", FontManager.music40, 0, 0, 32, 32);
    IconWidget next = new IconWidget("H", FontManager.music40, 0, 0, 32, 32);

    private double lastViewportLyricWidth = -1;
    private double lastViewportAnchorY = Double.NaN;

    /**
     * The single authoritative viewport for all lyric layers. It is derived from the
     * player''s live bounds each frame, so text, KTV composition and blur all follow resize.
     */
    private static final class LyricViewport {
        final double left;
        final double top;
        final double width;
        final double height;

        LyricViewport(double left, double top, double width, double height) {
            this.left = left;
            this.top = top;
            this.width = Math.max(0.0, width);
            this.height = Math.max(0.0, height);
        }

        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }
    }

    private final Music music;
    public MusicLyricsPanel(Music music) {
        this.music = music;
        updateLyricPositionsImmediate(getCurrentLyricViewportWidth());
    }

    private static void fetchTTMLLyrics(Music music, List<LyricLine> parsed) {

        MultiThreadingUtil.runAsync(() -> {
            try {
                String lrc = HttpUtils.getString("https://gitee.com/IzumiiKonata/amll-ttml-db/raw/main/ncm-lyrics/" + music.getId() + ".yrc", null);
//                System.out.println("歌曲 " + music.getName() + " 存在 ttml 歌词, 获取中...");

                ArrayList<LyricLine> lyricLines = new ArrayList<>();
                LyricParser.parseYrc(lrc, lyricLines);

                for (LyricLine bean : lyricLines) {

//                    System.out.println(bean.words.size());

                    for (LyricLine lyricLine : parsed) {
                        if (lyricLine.getLyric().toLowerCase().replace(" ", "").equals(bean.lyric.toLowerCase().replace(" ", ""))) {
                            bean.romanizationText = lyricLine.romanizationText;
                            bean.translationText = lyricLine.translationText;
                            break;
                        }
                    }

                }

//                CloudMusic.addLyrics(lyricLines);
            } catch (Exception ignored) {
            }
        });
    }

    public static void resetProgress(float progress) {
        CloudMusic.updateCurrentLyric(progress);
        CloudMusic.resetLyricPositionUpdate();
        updateLyricPositionsImmediate(getCurrentLyricViewportWidth());
    }

    public static double getLyricWidthFactor() {
        return .48;
    }

    /**
     * Keeps the animated lyric layer inside the player while it fades out.
     *
     * The old close animation used a maximum scale of 1.10, which made the
     * background, blur and glyph quads exceed the dynamically sized player.
     * At full opacity the content still uses the exact player size; during the
     * transition it only contracts, so resizing the player cannot expose a
     * second, larger lyric viewport.
     */
    private static double getContentScale(float alpha) {
        return LyricsPanelGeometry.contentScale(alpha);
    }

    /** Smooths the KTV sweep without decoupling it from the actual audio position. */
    private static double smoothKaraokeProgress(double value) {
        return LyricsPanelGeometry.smoothKaraokeProgress(value);
    }

    /** Individual characters ease into the active state in a short cascading wave. */
    private static double getCharacterKaraokeProgress(double wordProgress, int characterIndex) {
        return LyricsPanelGeometry.characterKaraokeProgress(wordProgress, characterIndex);
    }

    private static double getLyricLineSpacing() {
        return LyricsPanelGeometry.lyricLineSpacing();
    }

    private static double lyricFraction() {
        return LyricsPanelGeometry.lyricAnchorFraction();
    }

    /**
     * The lyric column deliberately uses the current player bounds rather than a global
     * screen fraction.  The right and vertical padding keep animated glyphs, their glow and
     * the blur kernel inside the live player window at every configured player scale.
     */
    private static LyricViewport createLyricViewport(double posX, double posY, double width, double height) {
        LyricsPanelGeometry.Viewport viewport = LyricsPanelGeometry.createViewport(posX, posY, width, height,
                NCMScreen.getInstance().getPlayerBorderThickness());
        return new LyricViewport(viewport.left, viewport.top, viewport.width, viewport.height);
    }

    public static void updateLyricPositionsImmediate(double width) {
        layoutLyricPositionsImmediately(width, getCurrentViewportAnchorY(), CloudMusic.currentLyric);
    }

    public static void updateLyricPositionsImmediate(double width, double playbackProgress) {
        layoutLyricPositionsImmediately(width, getCurrentViewportAnchorY(), CloudMusic.findCurrentLyric(playbackProgress));
    }

    private static double getCurrentLyricViewportWidth() {
        NCMScreen screen = NCMScreen.getInstance();
        return createLyricViewport(screen.getPanelX(), screen.getPanelY(),
                screen.getPanelWidth(), screen.getPanelHeight()).width;
    }

    private static double getCurrentViewportAnchorY() {
        NCMScreen screen = NCMScreen.getInstance();
        return screen.getPanelY() + screen.getPanelHeight() * lyricFraction();
    }

    /**
     * Rebuilds the complete lyric layout around one anchor line. Seeking can change the
     * active line by many entries at once, so every line must use the same anchor instead
     * of leaving a mixture of coordinates from the old and new playback positions.
     */
    private static void layoutLyricPositionsImmediately(double width, double anchorY, LyricLine anchorLyric) {
        if (anchorLyric == null) return;

        synchronized (CloudMusic.lyrics) {
            int anchorIndex = CloudMusic.lyrics.indexOf(anchorLyric);
            if (anchorIndex < 0 || anchorIndex >= CloudMusic.lyrics.size()) return;

            // Heights affect all following coordinates. Calculate them before assigning
            // positions so a wrapped/translated line cannot overlap its neighbour.
            for (LyricLine lyric : CloudMusic.lyrics) {
                lyric.computeHeight(width);
            }

            setLyricPosition(CloudMusic.lyrics.get(anchorIndex), anchorY);

            double offsetY = anchorY;
            for (int i = anchorIndex - 1; i >= 0; i--) {
                LyricLine lyric = CloudMusic.lyrics.get(i);
                offsetY -= lyric.height + getLyricLineSpacing();
                setLyricPosition(lyric, offsetY);
            }

            offsetY = anchorY + CloudMusic.lyrics.get(anchorIndex).height + getLyricLineSpacing();
            for (int i = anchorIndex + 1; i < CloudMusic.lyrics.size(); i++) {
                LyricLine lyric = CloudMusic.lyrics.get(i);
                setLyricPosition(lyric, offsetY);
                offsetY += lyric.height + getLyricLineSpacing();
            }
        }
    }

    private static void setLyricPosition(LyricLine lyric, double positionY) {
        lyric.posY = positionY;
        // setPosition also clears the spring velocity/queued target. This is required for
        // seeks, otherwise a line can keep interpolating from the previous timeline.
        lyric.spring.setPosition(positionY);
    }

    /**
     * The progress bar is dragged continuously. Keeping this as an immediate layout avoids
     * blending the old anchor positions with the preview anchor positions during a seek.
     */
    public static void updateLyricPositions(double width, double playbackProgress) {
        updateLyricPositionsImmediate(width, playbackProgress);
    }
    private static long getLyricInterpolationWaitTimeMillis() {
        return 75;
    }

    private static void resetLyricStatus() {
        CloudMusic.resetLyricStatus();
    }

    public void onInit() {
        resetLyricStatus();
    }

    public void close() {
        closing = true;
    }

    public boolean shouldClose() {
        return closing && alpha <= 0.02f;
    }

    public void onRender(double mouseX, double mouseY, double posX, double posY, double width, double height, int dWheel) {

        if (prevMouse && !Mouse.isButtonDown(0)) prevMouse = false;

        alpha = Interpolations.interpolate(alpha, closing ? 0.0f : 1f, 0.3f);

        // 面板尺寸动画过程中按真实视口重排。阈值可避免亚像素变化导致每帧重建全部歌词。
        LyricViewport lyricViewport = createLyricViewport(posX, posY, width, height);
        double viewportLyricWidth = lyricViewport.width;
        double viewportAnchorY = posY + height * lyricFraction();
        if (Math.abs(viewportLyricWidth - this.lastViewportLyricWidth) >= 2.0
                || Double.isNaN(this.lastViewportAnchorY)
                || Math.abs(viewportAnchorY - this.lastViewportAnchorY) >= 1.0) {
            layoutLyricPositionsImmediately(viewportLyricWidth, viewportAnchorY, CloudMusic.currentLyric);
            this.lastViewportLyricWidth = viewportLyricWidth;
            this.lastViewportAnchorY = viewportAnchorY;
        }

        // 渲染一帧只获取一次播放快照：本帧歌词、进度条、逐字高亮、时间文字共享同一 positionMs/durationMs
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();

        api.getGLStateManager().pushMatrix();
        double contentScale = getContentScale(alpha);
        scaleAtPos(posX + width * .5, posY + height * .5, contentScale);

        this.renderBackground(posX, posY, width, height, alpha, snapshot);
        this.renderControlsPart(mouseX, mouseY, posX, posY, width, height, alpha, snapshot);
        this.renderLyrics(mouseX, mouseY, posX, posY, width, height, dWheel, alpha, snapshot);
        api.getGLStateManager().popMatrix();
    }

    private void renderLyrics(double mouseX, double mouseY, double posX, double posY, double width, double height, int dWheel, float alpha, CloudMusic.PlaybackSnapshot snapshot) {

        if (CloudMusic.lyrics.isEmpty()) return;

        boolean playerNotReady = snapshot.player == null;
        double totalTimeMillis = snapshot.durationMs;
        double overridePlaybackProgress = progressBarProgressOverride * totalTimeMillis;
        double songProgress = playerNotReady ? 0 : (progressBarDragging ? overridePlaybackProgress : snapshot.positionMs);

        LyricViewport lyricViewport = createLyricViewport(posX, posY, width, height);
        if (lyricViewport.width <= .5 || lyricViewport.height <= .5) return;

        double lyricsWidth = lyricViewport.width;
        LyricLine displayedCurrentLyric = progressBarDragging ? CloudMusic.findCurrentLyric(overridePlaybackProgress) : CloudMusic.currentLyric;
        this.updateLyricPositions(posY, height, lyricsWidth, displayedCurrentLyric);

        List<Runnable> blurRects = new ArrayList<>();

        boolean hoveringLyrics = isHovered(mouseX, mouseY, lyricViewport.left, lyricViewport.top,
                lyricViewport.width, lyricViewport.height);

        if (hoveringLyrics && dWheel != 0) {

            double strength = 24;

            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) strength *= 2;

            if (dWheel > 0) scrollTarget += strength;
            else scrollTarget -= strength;

            scrollOffsetResetTimer.reset();

//            this.scrollTarget = Math.min(this.scrollTarget, 0);
        }

        if (scrollOffsetResetTimer.isDelayed(3000)) {
            scrollTarget = 0;
        }

        scrollOffset = Interpolations.interpolate(scrollOffset, scrollTarget, 0.25f);

        double lyricRenderOffsetX = lyricViewport.left;
        double lyricsViewportTop = lyricViewport.top;
        double lyricsViewportBottom = lyricViewport.bottom();
        double lyricsViewportHeight = lyricViewport.height;
        double lyricsViewportLeft = lyricViewport.left;
        double lyricsViewportRight = lyricViewport.right();

        // The parent player clip is rounded; this nested clip is the hard text viewport.
        // It remains on the Minecraft framebuffer stack and is restored after every KTV FBO pass.
        StencilClipManager.beginClip(() -> Rect.draw(lyricsViewportLeft, lyricsViewportTop,
                lyricsWidth, lyricsViewportHeight, -1));
        // The KTV renderer temporarily binds small word-sized framebuffers.  Stencil is
        // intentionally disabled there, so keep a screen-space clip active for every
        // direct lyric draw on the Minecraft framebuffer as the non-negotiable boundary.
        ScissorClipManager.begin(lyricsViewportLeft, lyricsViewportTop, lyricsWidth, lyricsViewportHeight);
        boolean lyricScissorActive = true;
        try {
            for (int k = 0; k < CloudMusic.lyrics.size(); k++) {
            LyricLine lyric = CloudMusic.lyrics.get(k);

            if (lyric.posY + lyric.height + getLyricLineSpacing() + scrollOffset < lyricsViewportTop) {
                continue;
            }

            if (lyric.posY + scrollOffset > lyricsViewportBottom) {
                break;
            }

            LyricLine currentLyric = displayedCurrentLyric;
            int indexOf = CloudMusic.lyrics.indexOf(currentLyric);

            boolean isCurrentLyric = lyric == currentLyric;
            lyric.alpha = Interpolations.interpolate(lyric.alpha, isCurrentLyric ? 1f : 0f, isCurrentLyric ? 0.1f : .05f);
            boolean isHovering = isHovered(mouseX, mouseY - scrollOffset, lyricRenderOffsetX, lyric.posY, lyricsWidth, lyric.height);
            lyric.hoveringAlpha = Interpolations.interpolate(lyric.hoveringAlpha, isHovering ? 1f : 0f, 0.2f);
            lyric.blurAlpha = Interpolations.interpolate(lyric.blurAlpha, !hoveringLyrics ? Math.min(1f, Math.abs(k - indexOf) * .85f) : 0f, 0.05f);

            if (isHovering) {
                CursorUtils.setOverride(CursorUtils.HAND);
            }

            if (isHovering && Mouse.isButtonDown(0) && !prevMouse) {
                prevMouse = true;
                if (snapshot.player != null) {
                    snapshot.player.setPlaybackTime(lyric.timestamp);
                    resetLyricStatus();
                    float actual = snapshot.player.getCurrentTimeMillis();
                    MusicLyricsWidget.resetProgress(actual);
                    resetProgress(actual);
                }

                scrollTarget = 0;
            }

            if (lyric.hoveringAlpha >= .02f)
                roundedRect(lyricRenderOffsetX - 4, lyric.posY + scrollOffset + lyric.reboundAnimation, lyricsWidth + lyric.reboundAnimation, lyric.height + 2, 8, 4 + 2 * Easing.EASE_IN_OUT_QUAD.getFunction().apply((double) lyric.hoveringAlpha), 1, 1, 1, alpha * lyric.hoveringAlpha * .15f);

            double renderX = lyricRenderOffsetX + lyric.reboundAnimation;
            double renderY = lyric.posY + lyric.reboundAnimation + scrollOffset;

            lyric.reboundAnimation = Interpolations.interpolate(lyric.reboundAnimation, isCurrentLyric ? 2f : 0f, 0.1f);

            List<LyricLine.Word> words = lyric.words;
            if (!words.isEmpty()) {
                for (LyricLine.Word word : words) {
                    double wordWidth = FontManager.pf65bold.getStringWidthD(word.word);

                    if (renderX + wordWidth >= lyricRenderOffsetX + lyricsWidth + lyric.reboundAnimation) {
                        renderX = lyricRenderOffsetX + lyric.reboundAnimation;
                        renderY += FontManager.pf65bold.getHeight() * .85 + 4;
                    }

                    if (!lyric.renderEmphasizes) Arrays.fill(word.emphasizes, 2);

                    double emphasizeWholeWord = word.emphasizes[0];

                    char[] charArray = word.word.toCharArray();

                    double emphasizeTarget = 1;
                    double emphasizeSpeed = 0.05;

                    if (isCurrentLyric) {
                        if (charArray.length > 1) {
                            double x = renderX;
                            for (int j = 0; j < charArray.length; j++) {
                                char c = charArray[j];

                                FontManager.pf65bold.drawString(String.valueOf(c), x, renderY - word.emphasizes[j], hexColor(1, 1, 1, alpha * .5f));
                                x += FontManager.pf65bold.getCharWidth(c, j + 1 < charArray.length ? charArray[j + 1] : '\0');
                            }
                        } else {
                            FontManager.pf65bold.drawString(word.word, renderX, renderY - word.emphasizes[0], hexColor(1, 1, 1, alpha * .5f));
                        }
                    } else {
                        FontManager.pf65bold.drawString(word.word, renderX, renderY, hexColor(1, 1, 1, alpha * .5f));
                    }

                    if (CloudMusic.lyrics.indexOf(currentLyric) - k <= 1) {
                        double progress = word.getProgress(songProgress);
                        double easedProgress = smoothKaraokeProgress(progress);
                        double stringWidthD = FontManager.pf65bold.getStringWidthD(word.word);

                        boolean shouldClip = progress > .001 && progress < .999;

                        if (progress >= .999) {
                            double x = renderX;
                            for (int j = 0; j < charArray.length; j++) {
                                char c = charArray[j];
                                if (lyric.renderEmphasizes) {
                                    word.emphasizes[j] = Interpolations.interpolate(word.emphasizes[j], 1.85, .12);
                                }
                                FontManager.pf65bold.drawString(String.valueOf(c), x,
                                        renderY - word.emphasizes[j], hexColor(1, 1, 1, alpha * lyric.alpha));
                                x += FontManager.pf65bold.getCharWidth(c, j + 1 < charArray.length ? charArray[j + 1] : '\0');
                            }
                        }

                        if (shouldClip) {
                            // The character textures are rendered into compact auxiliary FBOs.
                            // A screen-space scissor would be invalid in those FBO coordinate systems,
                            // so suspend it only for the off-screen generation pass.
                            ScissorClipManager.end();
                            lyricScissorActive = false;

                            int scale = 2;
                            int fbWidth = ((int) stringWidthD) * scale, fbHeight = (FontManager.pf65bold.getHeight() + 6) * scale;

//                            if (StencilClipManager.stencilClipping())
//                                GL11.glDisable(GL11.GL_STENCIL_TEST);

                            api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
                            api.getGLStateManager().pushMatrix();
                            api.getGLStateManager().loadIdentity();
                            api.getGLStateManager().ortho(0.0D, fbWidth * .5, fbHeight * .5, 0.0D, 1000.0D, 3000.0D);
                            api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);
                            api.getGLStateManager().pushMatrix();
                            api.getGLStateManager().loadIdentity();
                            api.getGLStateManager().translate(0.0F, 0.0F, -2000.0F);

                            // A wider alpha feather makes the colour sweep melt into the
                            // base lyric instead of switching colour at a sharp boundary.
                            double gradientWidth = Math.min(Math.max(10.0, FontManager.pf65bold.getHeight() * .42),
                                    Math.max(10.0, stringWidthD * .28));

                            // stencil texture
                            {
                                stencilFb = RenderSystem.createFrameBuffer(stencilFb, fbWidth, fbHeight);
                                stencilFb.bindFramebuffer(true);
                                stencilFb.setFramebufferColor(1, 1, 1, 0);
                                stencilFb.framebufferClearNoBinding();

                                StencilClipManager.disableStencilTest();

                                // 严格以真实播放前沿裁剪，渐变只位于已播放区域内部。
                                double front = progress * stringWidthD;
                                double activeGradientWidth = Math.min(gradientWidth, front);
                                double solidWidth = Math.max(0.0, front - activeGradientWidth);
                                Rect.draw(0, 0, solidWidth, FontManager.pf65bold.getHeight() + 6, -1);
                                RenderSystem.drawGradientRectLeftToRight(solidWidth, 0, front,
                                        FontManager.pf65bold.getHeight() + 6, -1, 0);
                            }

                            // base texture: every character gets a tiny cascading lift.
                            // It is continuous at fractional progress, avoiding the old
                            // integer character jump caused by `j <= prog`.
                            {
                                baseFb = RenderSystem.createFrameBuffer(baseFb, fbWidth, fbHeight);
                                baseFb.bindFramebuffer(true);
                                baseFb.setFramebufferColor(1, 1, 1, 0);
                                baseFb.framebufferClearNoBinding();

                                StencilClipManager.disableStencilTest();

                                double x = 0;
                                double characterTimeline = progress * charArray.length;
                                for (int j = 0; j < charArray.length; j++) {
                                    char c = charArray[j];
                                    double characterProgress = getCharacterKaraokeProgress(characterTimeline, j);
                                    if (lyric.renderEmphasizes) {
                                        double liftTarget = 1.85 * characterProgress;
                                        word.emphasizes[j] = Interpolations.interpolate(word.emphasizes[j], liftTarget, .16);
                                    }

                                    double characterAlpha = .70 + .30 * characterProgress;
                                    FontManager.pf65bold.drawString(String.valueOf(c), x, 2 - word.emphasizes[j],
                                            hexColor(1, 1, 1, (float) (alpha * lyric.alpha * characterAlpha)));
                                    x += FontManager.pf65bold.getCharWidth(c, j + 1 < charArray.length ? charArray[j + 1] : '\0');
                                }
                            }

                            Framebuffer.getMcFramebuffer().bindFramebuffer(true);
                            // FBO rendering disables stencil testing. Restore the
                            // rounded player clip before compositing the word texture.
                            StencilClipManager.restoreActiveClip();

                            api.getGLStateManager().popMatrix();
                            api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
                            api.getGLStateManager().popMatrix();
                            api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);

                            // Back on the Minecraft framebuffer: restore the viewport scissor
                            // before compositing the enlarged/glowing word texture.
                            ScissorClipManager.begin(lyricsViewportLeft, lyricsViewportTop, lyricsWidth, lyricsViewportHeight);
                            lyricScissorActive = true;

//                            if (StencilClipManager.stencilClipping())
//                                GL11.glEnable(GL11.GL_STENCIL_TEST);

                            // 当前字词在演唱期间以中心为锚点平滑放大，布局宽度保持不变，避免换行抖动。
                            double pulse = Math.sin(Math.PI * easedProgress);
                            double karaokeScale = 1.0 + Math.min(.14, Math.max(0.0, HudConfig.currentWordScale)) * pulse;
                            double wordCenterX = renderX + stringWidthD * .5;
                            double wordCenterY = renderY + FontManager.pf65bold.getHeight() * .5;
                            api.getGLStateManager().pushMatrix();
                            api.getGLStateManager().translate(wordCenterX, wordCenterY, 0);
                            api.getGLStateManager().scale(karaokeScale, karaokeScale, 1);
                            api.getGLStateManager().translate(-wordCenterX, -wordCenterY, 0);
                            Shaders.STENCIL.draw(baseFb.framebufferTexture, stencilFb.framebufferTexture, renderX, renderY - 2, fbWidth * .5, fbHeight * .5);
                            api.getGLStateManager().popMatrix();

                            if (ClientSettings.SHOW_WIDGET_BOUNDARY) {
//                                FontManager.pf18bold.drawString("Stencil: " + stencilFb.framebufferTextureWidth + "x" + stencilFb.framebufferTextureHeight, 50, 32, -1);
//                                FontManager.pf18bold.drawString("Base: " + baseFb.framebufferTextureWidth + "x" + baseFb.framebufferTextureHeight, 50, 64, -1);

                                double spacing = NCMScreen.getInstance().getSpacing();
                                Rect.draw(spacing, spacing, 400, (fbHeight * .5) * 3 + (20 * 2), 0xff000000);

                                api.getGLStateManager().enableTexture2D();
                                api.getGLStateManager().color(1, 1, 1, 1);

                                api.getGLStateManager().bindTexture(baseFb.framebufferTexture);
                                double xOff = spacing + 120;
                                ShaderProgram.drawQuadFlipped(xOff, spacing, fbWidth * .5, fbHeight * .5);

                                FontManager.pf28bold.drawCenteredStringVertical("Base Texture", spacing + 8, spacing + fbHeight * .25, -1);

                                api.getGLStateManager().bindTexture(stencilFb.framebufferTexture);
                                ShaderProgram.drawQuadFlipped(xOff, spacing + fbHeight * .5 + 20, fbWidth * .5, fbHeight * .5);

                                FontManager.pf28bold.drawCenteredStringVertical("Stencil Texture", spacing + 8, spacing + fbHeight * .5 + 20 + fbHeight * .25, -1);

                                Shaders.STENCIL.draw(baseFb.framebufferTexture, stencilFb.framebufferTexture, xOff, spacing + (fbHeight * .5) * 2 + 40, fbWidth * .5, fbHeight * .5);

                                FontManager.pf28bold.drawCenteredStringVertical("Result", spacing + 8, spacing + (fbHeight * .5) * 2 + 40 + fbHeight * .25, -1);

                            }

//                            Image.draw(stencilFb.framebufferTexture, 50, 72, stencilFb.framebufferTextureWidth * .5, stencilFb.framebufferTextureHeight * .5, Image.Type.Normal);
//                            Image.draw(baseFb.framebufferTexture, 50, 128, baseFb.framebufferTextureWidth * .5, baseFb.framebufferTextureHeight * .5, Image.Type.Normal);
//                            StencilClipManager.beginClip(() -> {
//                                Rect.draw(finalRenderX, finalRenderY - word.emphasize, progress * stringWidthD, FontManager.pf65bold.getHeight(), -1);
//                            });
                        }

                    } else {
                        FontManager.pf65bold.drawString(word.word, renderX, renderY - emphasizeWholeWord, hexColor(1, 1, 1, alpha * lyric.alpha));
                    }

                    renderX += wordWidth;
                }
            } else {
                String[] strings = FontManager.pf65bold.fitWidth(lyric.lyric, lyricsWidth);

                for (String string : strings) {
                    FontManager.pf65bold.drawString(string, renderX, renderY, hexColor(1, 1, 1, alpha * ((lyric.alpha * .6f) + .4f)));
                    renderY += FontManager.pf65bold.getHeight() * .85 + 4;
                }

                renderY -= FontManager.pf65bold.getHeight() * .85 + 4;
            }

            if (lyric.translationText != null) {
                double translationX = lyricRenderOffsetX + lyric.reboundAnimation;
                double translationY = renderY + FontManager.pf65bold.getHeight() * .85 + 8;

                String[] strings = FontManager.pf34bold.fitWidth(lyric.translationText, lyricsWidth);
                for (String string : strings) {
                    FontManager.pf34bold.drawString(string, translationX, translationY, hexColor(1, 1, 1, alpha * .75f * ((lyric.alpha * .6f) + .4f)));
                    translationY += FontManager.pf34bold.getHeight() + 4;
                }
//                FontManager.pf34bold.drawString(lyric.translationText, translationX, translationY, hexColor(1, 1, 1, alpha * .75f * ((lyric.alpha * .6f) + .4f)));
            }

            // 先把源矩形与歌词视口求交，减少模糊输入在视口外的扩散。
            double blurLeft = Math.max(lyricsViewportLeft, lyricRenderOffsetX - 4);
            double blurTop = Math.max(lyricsViewportTop, lyric.posY + scrollOffset);
            double blurRight = Math.min(lyricsViewportRight, lyricRenderOffsetX + lyricsWidth);
            double blurBottom = Math.min(lyricsViewportBottom, lyric.posY + scrollOffset + lyric.height + 8);
            if (blurRight > blurLeft && blurBottom > blurTop) {
                double clippedBlurWidth = blurRight - blurLeft;
                double clippedBlurHeight = blurBottom - blurTop;
                blurRects.add(() -> Rect.draw(blurLeft, blurTop, clippedBlurWidth, clippedBlurHeight,
                        hexColor(1, 1, 1, alpha * lyric.blurAlpha)));
            }
        }

        // The blur input/output framebuffers use the full game resolution.  Keep the same
        // lyric viewport scissor active through both passes and final composition; do not
        // cancel the content transform here, otherwise the projected scissor no longer
        // matches the dynamically scaled player bounds.
        Shaders.BLUR_SHADER.run(blurRects);
        // The blur pass returns from a full-screen auxiliary framebuffer.
        // Re-enable the parent rounded-player clip before any later panel content is rendered.
        StencilClipManager.restoreActiveClip();
        } finally {
            if (lyricScissorActive) {
                ScissorClipManager.end();
            }
            // Blur/FBO rendering can toggle raw stencil state. Restore the lyric viewport
            // before leaving it, then pop only the clip introduced by this method.
            StencilClipManager.restoreActiveClip();
            StencilClipManager.endClip();
        }
    }

    private void updateLyricPositions(double posY, double height, double width, LyricLine currentLyric) {

        if (currentLyric == null) return;

        int idxCurrent = CloudMusic.lyrics.indexOf(currentLyric);

        if (idxCurrent < 0 || idxCurrent >= CloudMusic.lyrics.size()) return;
//
        double offsetY = posY + height * lyricFraction()/* - (idxCurrent > 0 ? CloudMusic.lyrics.get(idxCurrent - 1).height : 0)*/;

        synchronized (CloudMusic.lyrics) {
            List<LyricLine> subList = CloudMusic.lyrics.subList(0, idxCurrent);
            double frameDeltaTime = RenderSystem.getFrameDeltaTime() * .0125;
            for (int i = subList.size() - 1; i >= 0; i--) {
                LyricLine lyric = subList.get(i);

                lyric.computeHeight(width);
                offsetY -= lyric.height + getLyricLineSpacing();

                if ((scrollTarget == 0 && (subList.size() - 1 - i) >= 3) && lyric.posY + lyric.height + getLyricLineSpacing() + 2 + scrollOffset < posY)
                    break;

//                lyric.posY = Interpolations.interpolate(lyric.posY, offsetY, fraction);
                lyric.spring.setTargetPosition(offsetY);
                lyric.spring.update(frameDeltaTime);
                lyric.posY = lyric.spring.getCurrentPosition();
            }

            offsetY = posY + height * lyricFraction();
            List<LyricLine> list = CloudMusic.lyrics.subList(idxCurrent, CloudMusic.lyrics.size());
            int oobCounter = 0;
            for (LyricLine lyric : list) {
                int j = CloudMusic.lyrics.indexOf(lyric);

//                Rect.draw(RenderSystem.getWidth() * .5 + lyric.reboundAnimation, lyric.posY, width, lyric.height, 0x80FFFFFF);

                lyric.computeHeight(width);

                LyricLine prev = j > 0 ? CloudMusic.lyrics.get(j - 1) : null;

                if (prev != null) {
                    if (prev.delayTimer.isDelayed(getLyricInterpolationWaitTimeMillis()))
                        lyric.shouldUpdatePosition = true;
//                    if (lyric.posY - (prev.posY) >= prev.height * 1.5)
//                        lyric.shouldUpdatePosition = true;
                }

//                if (prev != null && lyric.posY - (prev.posY + prev.height) < 0) {
//                    updateLyricPositionsImmediate(width);
//                    break;
//                }

                if (prev != null && !lyric.shouldUpdatePosition) {
                    lyric.delayTimer.reset();
                    break;
                }

                if (prev == null && !lyric.delayTimer.isDelayed(getLyricInterpolationWaitTimeMillis())) break;

                lyric.spring.setTargetPosition(offsetY);
                lyric.spring.update(frameDeltaTime);
                lyric.posY = lyric.spring.getCurrentPosition();

                if (offsetY + scrollOffset > posY + height) {
                    oobCounter += 1;

                    if (oobCounter >= 4 && scrollTarget == 0) break;
                }

                offsetY += lyric.height + getLyricLineSpacing();
            }
        }

    }

    private double getCoverSizeMax() {
        NCMScreen screen = NCMScreen.getInstance();
        return getCoverSizeMax(screen.getPanelWidth(), screen.getPanelHeight());
    }

    private double getCoverSizeMax(double width, double height) {
        return LyricsPanelGeometry.coverSizeMax(width, height);
    }

    private double getCoverSizeMin() {
        return getCoverSizeMax() * .8;
    }

    private void renderControlsPart(double mouseX, double mouseY, double posX, double posY, double width, double height, float alpha, CloudMusic.PlaybackSnapshot snapshot) {
        TextureManager textureManager = TextureManager.getInstance();

        AudioPlayer player = snapshot.player;

        double coverSizeMax = this.getCoverSizeMax(width, height);
        double coverSizeMin = coverSizeMax * .8;
        double coverCenterX = posX + width * .24;
        double coverCenterY = posY + height * .36;
        coverSize = Interpolations.interpolate(coverSize, player == null || player.isPausing() ? coverSizeMin : coverSizeMax, 0.2f);

        double coverSizePerc = coverSize / coverSizeMax;
        double coverRadius = 7;

        api.getGLStateManager().pushMatrix();
        this.scaleAtPos(posX + width * .5, posY + height * .5, (.925 + (alpha * 0.075)));
        Shaders.BLOOM_SHADER.run(Collections.singletonList(() -> {
            this.roundedRect(coverCenterX - coverSize * .5, coverCenterY - coverSize * .575, coverSize, coverSize, coverRadius * coverSizePerc, -.5, 0, 0, 0, alpha * .4f);
        }));
        api.getGLStateManager().popMatrix();

        api.getGLStateManager().disableAlpha();

        if (prevCover != null && coverAlpha <= .9f) {
            api.getGLStateManager().bindTexture(prevCover.getGlTextureId());
            this.roundedRectTextured(coverCenterX - coverSize * .5, coverCenterY - coverSize * .575, coverSize, coverSize, coverRadius * coverSizePerc, alpha);
        }

        Location musicCover = snapshot.music.getCoverLocation();
        ITextureObject tex = textureManager.getTexture(musicCover);

        if (tex != null) {
            coverAlpha = Interpolations.interpolate(coverAlpha, 1.0f, 0.2f);
            api.getGLStateManager().bindTexture(tex.getGlTextureId());
            tex.linearFilter();
            this.roundedRectTextured(coverCenterX - coverSize * .5, coverCenterY - coverSize * .575, coverSize, coverSize, coverRadius * coverSizePerc, alpha * coverAlpha);
        }

        double elementsXOffset = coverCenterX - coverSizeMax * .5;
        double elementsYOffset = coverCenterY + coverSizeMax * .45 + 8;

        stMusicName.render(FontManager.pf28bold, snapshot.music.getName(), elementsXOffset, elementsYOffset, coverSizeMax, RGBA.color((float) 1, (float) 1, (float) 1, alpha));
        stArtists.render(FontManager.pf20bold, snapshot.music.getArtistsName(), elementsXOffset, elementsYOffset + FontManager.pf20bold.getHeight() + 8, coverSizeMax, RGBA.color((float) 1, (float) 1, (float) 1, alpha * .8f));

        // progressbar 背景
        double progressBarYOffset = elementsYOffset + FontManager.pf20bold.getHeight() + 8 + FontManager.pf20bold.getHeight() + 8;
        double progressBarWidth = coverSizeMax;

        roundedRect(elementsXOffset, progressBarYOffset - progressBarHeight * .5, progressBarWidth, progressBarHeight, (this.progressBarHeight / 8.0f) * 2.5, hexColor(1, 1, 1, alpha * .5f));

        double currentTimeMillis = snapshot.positionMs;
        double totalTimeMillis = snapshot.durationMs;
        double perc = player == null ? 0 : (progressBarDragging ? progressBarProgressOverride : currentTimeMillis / Math.max(1.0, totalTimeMillis));

        // 播放进度条填充：MC 1.8.9 主 framebuffer 无 stencil，stencil 裁剪失效，改为按 perc 直接画 partial 宽度圆角矩形。
        double fillWidth = progressBarWidth * perc;
        if (fillWidth > 0)
            roundedRect(elementsXOffset, progressBarYOffset - progressBarHeight * .5, fillWidth, progressBarHeight, Math.min((this.progressBarHeight / 8.0f) * 2.5, fillWidth * .5), hexColor(1, 1, 1, alpha));

        boolean hoveringProgressBar = progressBarDragging || this.isHovered(mouseX, mouseY, elementsXOffset, progressBarYOffset - progressBarHeight * .5, progressBarWidth, 8);
        this.progressBarHeight = Interpolations.interpolate(this.progressBarHeight, hoveringProgressBar ? 8 : 5, 0.3f);

        boolean lmbDown = Mouse.isButtonDown(0);
        if (hoveringProgressBar && lmbDown && !prevMouse) {
            prevMouse = true;
            progressBarDragging = true;

            double xDelta = Math.max(0, Math.min(progressBarWidth, (mouseX - elementsXOffset)));
            this.progressBarProgressOverride = xDelta / progressBarWidth;
            updateLyricPositionsImmediate(createLyricViewport(posX, posY, width, height).width, progressBarProgressOverride * totalTimeMillis);
        }

        if (progressBarDragging) {
            if (!lmbDown) {
                progressBarDragging = false;

                double percent = this.progressBarProgressOverride;

                if (player != null) {
                    float progress = (float) (percent * totalTimeMillis);
                    player.setPlaybackTime(progress);
                    // Seek 后立即读取真实 positionMs，用实际音频时钟同步歌词/进度（不依赖请求值）
                    float actual = player.getCurrentTimeMillis();
                    MusicLyricsWidget.resetProgress(actual);
                    MusicLyricsPanel.resetProgress(actual);
                    scrollTarget = scrollOffset = 0;
                }
            } else {
                double xDelta = Math.max(0, Math.min(progressBarWidth, (mouseX - elementsXOffset)));
                this.progressBarProgressOverride = xDelta / progressBarWidth;
                updateLyricPositions(createLyricViewport(posX, posY, width, height).width, progressBarProgressOverride * totalTimeMillis);
            }
        }

        // curTime
        float curTime = progressBarDragging ? (float) (progressBarProgressOverride * totalTimeMillis) : (float) currentTimeMillis;
        FontManager.pf12bold.drawString(formatDuration(curTime), elementsXOffset, progressBarYOffset + 8, hexColor(1, 1, 1, alpha * .5f));
        String remainingTime = "-" + formatDuration((float) (totalTimeMillis - curTime));
        FontManager.pf12bold.drawString(remainingTime, elementsXOffset + progressBarWidth - FontManager.pf12bold.getStringWidthD(remainingTime), progressBarYOffset + 8, hexColor(1, 1, 1, alpha * .5f));

        double volumeBarYOffset = posY + height - Math.max(18, height * .08);
        double volumeBarWidth = coverSizeMax - FontManager.music40.getStringWidthD("I")
                - FontManager.music40.getStringWidthD("J");
        double volumeBarXOffset = elementsXOffset + FontManager.music40.getStringWidthD("I") - 2;
        // Both player surfaces delegate to this exact control so drag behavior, theme colors,
        // hover animation and Dynamic Island feedback stay synchronized.
        boolean hoveringVolumeBar = volumeControl.render(mouseX, mouseY, elementsXOffset - 8,
                volumeBarYOffset, progressBarWidth + 12.0, alpha);

        if (hoveringProgressBar || hoveringVolumeBar) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }
        playPauseButton.setAlpha(alpha);
        playPauseButton.setWidth(32);
        playPauseButton.setHeight(32);
        playPauseButton.setPosition(volumeBarXOffset + volumeBarWidth * .5 - playPauseButton.getWidth() * .5, progressBarYOffset + (volumeBarYOffset - progressBarYOffset) * .5 - playPauseButton.getHeight() * .5);
        playPauseButton.renderWidget(mouseX, mouseY, 0);
        playPauseButton.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));

        playPauseButton.setBeforeRenderCallback(() -> {
            if (CloudMusic.player == null || CloudMusic.player.isPausing()) {
                playPauseButton.setIcon("G");
            } else {
                playPauseButton.setIcon("F");
            }
        });

        playPauseButton.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null) {
                    if (CloudMusic.player.isPausing()) CloudMusic.player.unpause();
                    else CloudMusic.player.pause();

                }
            }

            return true;
        });

        playPauseButton.fontOffsetY = 0;
        prev.setAlpha(alpha);
        prev.setWidth(32);
        prev.setHeight(32);
        prev.setPosition(volumeBarXOffset + volumeBarWidth * .5 - playPauseButton.getWidth() * .5 - 16 - prev.getWidth(), playPauseButton.getY());
        prev.renderWidget(mouseX, mouseY, 0);
        prev.fr = FontManager.music40;
        prev.fontOffsetY = 0;
        prev.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));

        prev.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null) CloudMusic.prev();
            }

            return true;
        });

        next.setAlpha(alpha);
        next.setWidth(32);
        next.setHeight(32);
        next.setPosition(volumeBarXOffset + volumeBarWidth * .5 + playPauseButton.getWidth() * .5 + 16, playPauseButton.getY());
        next.renderWidget(mouseX, mouseY, 0);
        next.fr = FontManager.music40;
        next.fontOffsetY = 0;
        next.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));

        next.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null) CloudMusic.next();
            }

            return true;
        });
    }

    public void mouseClicked(double mouseX, double mouseY, int mouseButton) {
        playPauseButton.onMouseClickReceived(mouseX, mouseY, mouseButton);
        prev.onMouseClickReceived(mouseX, mouseY, mouseButton);
        next.onMouseClickReceived(mouseX, mouseY, mouseButton);
    }

    private String formatDuration(float totalMillis) {
        return LyricsPanelGeometry.formatDuration(totalMillis);
    }

    private void renderBackground(double posX, double posY, double width, double height, float alpha, CloudMusic.PlaybackSnapshot snapshot) {
        TextureManager textureManager = TextureManager.getInstance();
        Music current = snapshot.music;
        Location musicCoverBlurred = current == null ? null : current.getBlurredCoverLocation();
        ITextureObject texBg = current == null ? null : textureManager.getTexture(musicCoverBlurred);

        if (current != null && current != prevMusic) {

            if (prevMusic != null) musicBgAlpha = 0.0f;

            prevBg = prevMusic == null ? null : textureManager.getTexture(prevMusic.getBlurredCoverLocation());
            prevCover = prevMusic == null ? null : textureManager.getTexture(prevMusic.getCoverLocation());
            prevMusic = current;
            coverAlpha = 0.0f;
        }

        // The blurred cover is intentionally oversized to keep its crop stable while the
        // player resizes.  Do not rely solely on the parent stencil: auxiliary FBO/shader
        // passes may temporarily disable it.  A projected scissor is the hard boundary that
        // keeps this oversized texture inside the live player rectangle at every scale.
        StencilClipManager.restoreActiveClip();
        ScissorClipManager.begin(posX, posY, width, height);
        try {
            if (texBg != null || prevBg != null) {
                api.getGLStateManager().pushMatrix();
                try {
                    double bgSize = Math.max(width, height);

                    if (prevBg != null && musicBgAlpha < 0.99f) {
                        int glTextureId = prevBg.getGlTextureId();
                        api.getGLStateManager().bindTexture(glTextureId);
                        prevBg.linearFilter();
                        api.getGLStateManager().color(1, 1, 1, alpha);
                        Image.draw(posX + width * .5 - bgSize * .5, posY + height * .5 - bgSize * .5,
                                bgSize, bgSize, Image.Type.NoColor);
                    }

                    if (texBg != null) {
                        this.musicBgAlpha = Interpolations.interpolate(this.musicBgAlpha, 1.0f,
                                prevBg == null ? 0.15f : 0.05f);
                        api.getGLStateManager().bindTexture(texBg.getGlTextureId());
                        texBg.linearFilter();
                        api.getGLStateManager().color(1, 1, 1, alpha * this.musicBgAlpha);
                        Image.draw(posX + width * .5 - bgSize * .5, posY + height * .5 - bgSize * .5,
                                bgSize, bgSize, Image.Type.NoColor);
                    }
                } finally {
                    api.getGLStateManager().popMatrix();
                }
            }

            Rect.draw(posX, posY, width, height, hexColor(0, 0, 0, alpha * .35f));
        } finally {
            ScissorClipManager.end();
            // Image rendering changes raw GL state; the enclosing rounded-player clip must
            // remain valid for controls and lyric layers rendered afterwards.
            StencilClipManager.restoreActiveClip();
        }
    }

}
