package com.muoniumplayer.core.widget.impl;

import today.opai.api.enums.EnumModuleCategory;
import today.opai.api.features.ExtensionModule;
import today.opai.api.features.ExtensionWidget;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.modules.values.BooleanValue;
import today.opai.api.interfaces.modules.values.NumberValue;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.entities.impl.ScrollText;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.WidgetWrapper;

import java.awt.*;
import java.time.Duration;

/**
 * @author IzumiiKonata
 * Date: 2025/2/16 14:19
 */
public class MusicInfoWidget extends ExtensionModule implements SharedConstants, SharedRenderingConstants, EventHandler {

    public BooleanValue turnComposerIntoLyric = api.getValueManager().createBoolean("Turn Composer Into Lyric", false);

    /** 网易云动态封面(需要 ffmpeg 抽帧)。关掉后桌面歌曲信息与全屏歌词页一律用静态封面。 */
    public BooleanValue animatedCover = api.getValueManager().createBoolean("Animated Cover", true);

    public NumberValue volume = api.getValueManager().createDouble("Volume", 0.1, 0.0, 1.0, 0.01);

    public ExtensionWidget widget;
    WidgetWrapper.WidgetPosSizeInterface posInterface;

    public MusicInfoWidget() {
        super("Music Info", "Shows music info.", EnumModuleCategory.VISUAL);
        Tuple<ExtensionWidget, WidgetWrapper.WidgetPosSizeInterface> wrapper = WidgetWrapper.createWrapper(this, this::onRender);
        this.widget = wrapper.getA();
        this.posInterface = wrapper.getB();

        volume.setHiddenPredicate(() -> true);
        volume.setValueCallback(val -> {
            if (CloudMusic.player != null)
                CloudMusic.player.setVolume(val.floatValue());
        });
        
        // 动态封面开关的真实来源是 HudConfig(HUD 编辑器"封面 → 动态封面"同一状态),这里只是另一处入口。
        animatedCover.setValueCallback(val -> {
            if (val == null || HudConfig.animatedCoverEnabled == val) return;
            HudConfig.animatedCoverEnabled = val;
            HudConfig.save();
        });

        this.addValues(this.turnComposerIntoLyric, this.animatedCover, this.volume);
        this.setEventHandler(this);
    }

    float alpha = 0.0f;

    ScrollText musicName = new ScrollText();
    ScrollText artists = new ScrollText();

    public double downloadProgHeight = 0;
    public boolean downloading = false;
    public double downloadProgress = 0;
    public String downloadSpeed = "0 b/s";
    float downloadPanelAlpha = 0.0f;

    float musicBgAlpha = 0.0f;
    ITextureObject prevBlurredBg = null;
    ITextureObject prevBg = null;
    Music prevMusic = null;

    public void onRender() {

        // HUD 位置 + 缩放（HudConfig，由 GuiHudEditor 拖拽/滚轮调整）。
        // 位置公式与参考实现一致：pixel = pos * (screen - baseSize * scale)；缩放围绕左上锚点等比进行。
        float hudScale = HudConfig.infoScale;
        float hudX = (float) (HudConfig.infoX * (getWidth() - 230 * hudScale));
        float hudY = (float) (HudConfig.infoY * (getHeight() - 56 * hudScale));
        posInterface.setX(hudX);
        posInterface.setY(hudY);

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(hudX, hudY, 0);
        api.getGLStateManager().scale(hudScale, hudScale, 1);
        api.getGLStateManager().translate(-hudX, -hudY, 0);

        double width = 230;
        double height = 56;

        Music playingMusic = CloudMusic.currentlyPlaying;

        boolean playing = playingMusic != null && CloudMusic.player != null && !CloudMusic.player.isFinished();

        alpha = Interpolations.interpolate(alpha, playing ? 1 : 0, playing ? 0.15f : 0.2f);

        this.downloadProgHeight = Interpolations.interpolate(this.downloadProgHeight, this.downloading ? (playing ? 26 : -26) : 0, 0.2f);
        this.downloadPanelAlpha = Interpolations.interpolate(this.downloadPanelAlpha, this.downloading ? 1.0f : 0.0f, 0.4f);

        if (playingMusic != null) {

            // 动态封面就绪时优先用它;没有(或用户关掉)时仍是静态小封面。
            Location cover = CloudMusic.preferredCoverLocation(playingMusic, playingMusic.getSmallCoverLocation());
            ITextureObject texture = TextureManager.getInstance().getTexture(cover);

            double imgSpacing = 4;

            double imgX = posInterface.getX() + imgSpacing;

            float y = (float) (posInterface.getY() + downloadProgHeight);
            double imgY = y + imgSpacing;

            double imgSize = height - imgSpacing * 2;

            double coverRound = 6;
            double bgRound = coverRound * 1.75;

            api.getGLStateManager().pushMatrix();

            api.getGLStateManager().translate(posInterface.getX() + posInterface.getWidth() * .5, posInterface.getY() + posInterface.getHeight() * .5, 0);
            double scale = .98 + (alpha * .02);
            api.getGLStateManager().scale(scale, scale, 1);
            api.getGLStateManager().translate(-(posInterface.getX() + posInterface.getWidth() * .5), -(posInterface.getY() + posInterface.getHeight() * .5), 0);

            api.getShaderUtil().drawWithBloom(() -> {
                api.getGLStateManager().pushMatrix();

                api.getGLStateManager().translate(posInterface.getX() + posInterface.getWidth() * .5, posInterface.getY() + posInterface.getHeight() * .5, 0);
                api.getGLStateManager().scale(scale, scale, 1);
                api.getGLStateManager().translate(-(posInterface.getX() + posInterface.getWidth() * .5), -(posInterface.getY() + posInterface.getHeight() * .5), 0);

                this.roundedRect(posInterface.getX(), posInterface.getY(), width, height + downloadProgHeight, bgRound, 1, 0, 0, 0, alpha * 0.7f);

                api.getGLStateManager().popMatrix();
            });

            {

                double posX = posInterface.getX();
                double posY = posInterface.getY();

                Location musicCoverBlurred = CloudMusic.currentlyPlaying.getBlurredCoverLocation();

                TextureManager textureManager = TextureManager.getInstance();
                ITextureObject texBg = textureManager.getTexture(musicCoverBlurred);

                if (texBg != null || prevBlurredBg != null) {

                    if (playingMusic != prevMusic) {
                        prevBlurredBg = prevMusic == null ? null : textureManager.getTexture(prevMusic.getBlurredCoverLocation());
                        prevBg = prevMusic == null ? null : textureManager.getTexture(prevMusic.getCoverLocation());
                        prevMusic = playingMusic;
                        musicBgAlpha = 0.0f;
                    }

                    double v = (height) / width;

                    if (prevBlurredBg != null && musicBgAlpha < 0.99f) {
                        api.getGLStateManager().bindTexture(prevBlurredBg.getGlTextureId());
                        prevBlurredBg.linearFilter();
                        this.roundedRectTextured(posX, posY, width, height + downloadProgHeight, 0, v, 1, v, bgRound, 1, alpha);
                    }

                    if (texBg != null) {
                        this.musicBgAlpha = Interpolations.interpolate(this.musicBgAlpha, 1.0f, 0.3f);
                        api.getGLStateManager().bindTexture(texBg.getGlTextureId());
                        texBg.linearFilter();
                        this.roundedRectTextured(posX, posY, width, height + downloadProgHeight, 0, .5 - v * .5, 1, v, bgRound, 1, this.musicBgAlpha * alpha);
                    }

                }
            }

            this.roundedRect(posInterface.getX(), posInterface.getY(), width, height + downloadProgHeight, bgRound, 1, 0, 0, 0, alpha * 0.25f);

            // render download panel

            if (this.downloading) {

                double offsetY = posInterface.getY() + imgSpacing;

                CFontRenderer fr = FontManager.pf18bold;

                fr.drawString("Downloading...", imgX, offsetY, new Color(1, 1, 1, downloadPanelAlpha).getRGB());
                fr.drawString(downloadSpeed, imgX + width - imgSpacing * 2 - fr.getWidth(downloadSpeed), offsetY, new Color(1, 1, 1, downloadPanelAlpha).getRGB());

                this.roundedRect(imgX, offsetY + fr.getHeight() + 4, width - imgSpacing * 2, 6, 2, 1, 1, 1, downloadPanelAlpha * 0.25f);

                double downloadFillWidth = (width - imgSpacing * 2) * downloadProgress;
                if (downloadFillWidth > 0)
                    this.roundedRect(imgX, offsetY + fr.getHeight() + 4, downloadFillWidth, 6, Math.min(2, downloadFillWidth * .5), 1, 1, 1, downloadPanelAlpha);
            }

            if (prevBg != null) {
                api.getGLStateManager().bindTexture(prevBg.getGlTextureId());
                prevBg.linearFilter();
                double exp = 0;
                this.roundedRectTextured(imgX - exp, imgY - exp, imgSize + exp * 2, imgSize + exp * 2, coverRound, alpha);
            }

            if (texture != null) {
                api.getGLStateManager().bindTexture(texture.getGlTextureId());
                texture.linearFilter();
                double exp = 0;
                this.roundedRectTextured(imgX - exp, imgY - exp, imgSize + exp * 2, imgSize + exp * 2, coverRound, this.musicBgAlpha * alpha);
            }

            String secondaryText = playingMusic.getArtistsName();

            if (this.turnComposerIntoLyric.getValue() && CloudMusic.player != null) {
                LyricLine currentDisplaying = CloudMusic.currentLyric;
                LyricLine next = null;

                if (!CloudMusic.lyrics.isEmpty()) {
                    int currentIndex = CloudMusic.lyrics.indexOf(currentDisplaying);
                    if (currentIndex >= 0 && currentIndex < CloudMusic.lyrics.size() - 1) {
                        next = CloudMusic.lyrics.get(currentIndex + 1);
                    }
                }

                if (currentDisplaying != null) {
                    secondaryText = currentDisplaying.getLyric();
                    artists.setWaitTime(100L);
                    artists.setOneShot(true);

                    if (next != null) {
                        artists.anim.setDuration(Duration.ofMillis(next.timestamp - currentDisplaying.timestamp - 500));
                    } else {
                        artists.anim.setDuration(Duration.ofMillis((long) (CloudMusic.player.getCurrentTimeMillis() - currentDisplaying.timestamp - 500)));
                    }

                } else {
                    artists.setWaitTime(2000L);
                    artists.setOneShot(false);
                    artists.anim.setDuration(Duration.ofMillis(0));
                }
            } else {
                artists.setWaitTime(2000L);
                artists.setOneShot(false);
                artists.anim.setDuration(Duration.ofMillis(0));
            }

            double progressBarWidth = width - (imgSize + imgSpacing * 3.25);

            String name1 = playingMusic.getName();

            double musicNameY = imgY + 3;
            musicName.render(FontManager.pf25bold, name1, imgX + imgSize + imgSpacing, musicNameY, progressBarWidth, new Color(1f, 1f, 1f, alpha).getRGB());

            double progressBarOffsetY = y + height - imgSpacing - 3 - FontManager.pf14bold.getFontHeight() - 8;

            artists.render(FontManager.pf20, secondaryText, imgX + imgSize + imgSpacing, musicNameY + FontManager.pf25bold.getFontHeight() + (progressBarOffsetY - (musicNameY + FontManager.pf25bold.getFontHeight())) * .5 - FontManager.pf20.getFontHeight() * .5, progressBarWidth, new Color(1f, 1f, 1f, alpha * 0.8f).getRGB());

            this.roundedRect(imgX + imgSize + imgSpacing, progressBarOffsetY, progressBarWidth, 5, 1, 1f, 1f, 1f, alpha * 0.3f);

            if (CloudMusic.player != null) {
                double totalTimeMillis = CloudMusic.player.getTotalTimeMillis();
                double playbackFillWidth = 0.0;
                if (totalTimeMillis > 0.0) {
                    double progress = Math.max(0.0,
                            Math.min(1.0, CloudMusic.player.getCurrentTimeMillis() / totalTimeMillis));
                    playbackFillWidth = progressBarWidth * progress;
                }
                if (playbackFillWidth > 0)
                    this.roundedRect(imgX + imgSize + imgSpacing, progressBarOffsetY, playbackFillWidth, 5, Math.min(1, playbackFillWidth * .5), 233, 233, 233, (int) (alpha * 255));

                int cMin = (int) (CloudMusic.player.getCurrentTimeSeconds() / 60);
                int cSec = (int) (CloudMusic.player.getCurrentTimeSeconds() - cMin * 60);
                String currentTime = (cMin < 10 ? "0" + cMin : cMin) + ":" + (cSec < 10 ? "0" + cSec : cSec);
                int tMin = (int) (CloudMusic.player.getTotalTimeSeconds() / 60);
                int tSec = (int) (CloudMusic.player.getTotalTimeSeconds() - tMin * 60);
                String totalTime = (tMin < 10 ? "0" + tMin : tMin) + ":" + (tSec < 10 ? "0" + tSec : tSec);

                int textColor = RGBA.color(255, 255, 255, (int) (alpha * 128));
                double playbackTimeY = progressBarOffsetY + 9;
                FontManager.pf14bold.drawString(currentTime, imgX + imgSize + imgSpacing, playbackTimeY, textColor);
                FontManager.pf14bold.drawString(totalTime, imgX + imgSize + imgSpacing + progressBarWidth - FontManager.pf14bold.getStringWidthD(totalTime), playbackTimeY, textColor);

            }

            api.getGLStateManager().popMatrix();
        }

        posInterface.setWidth((float) width);
        posInterface.setHeight((float) (height + downloadProgHeight));

        api.getGLStateManager().popMatrix();
    }

}
