package com.muoniumplayer.core.screens.ncm.panels;

import org.lwjgl.input.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PersonalFmManager;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.MusicLyricsPanel;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.VolumeControl;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;


/**
 * @author IzumiiKonata
 * Date: 2025/10/17 21:24
 */
public class ControlsBar extends NCMPanel {

    public ControlsBar() {
    }

    @Override
    public void onInit() {
        RectWidget bg = new RectWidget();

        this.addChild(bg);

        bg.setBeforeRenderCallback(() -> {
            bg.setMargin(0);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND));
            bg.setAlpha(.95f - NCMTheme.getLiquidGlassAmount() * .22f);
        });

        RoundedImageWidget playingCover = new RoundedImageWidget(() -> {
            if (CloudMusic.currentlyPlaying == null)
                return null;

            return CloudMusic.currentlyPlaying.getSmallCoverLocation();
        }, 0 , 0, 0, 0);

        this.addChild(playingCover);

        playingCover
                .fadeIn()
                .setLinearFilter(true)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> playingCover
                        .setMargin(5)
                        .setBounds(playingCover.getHeight(), playingCover.getHeight())
                        .setRadius(2))
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (CloudMusic.currentlyPlaying != null) {
                        NCMScreen.getInstance().musicLyricsPanel = new MusicLyricsPanel(CloudMusic.currentlyPlaying);
                    }

                    return true;
                });
        double buttonsYOffset = -4;

        IconWidget playPause = new IconWidget("B", FontManager.icon30, 0, 0, 20, 20);

        this.addChild(playPause);

        playPause
                .setBeforeRenderCallback(() -> {
                    boolean showPausingIcon = CloudMusic.player == null || CloudMusic.player.isPausing();

                    playPause
                            .center()
                            .setIcon(showPausingIcon ? "B" : "A")
                            .setPosition(playPause.getRelativeX(), playPause.getRelativeY() + buttonsYOffset)
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                })
                .setOnClickCallback((x, y, i) -> {
                    boolean hasCurrentlyPlaying = CloudMusic.player != null && CloudMusic.currentlyPlaying != null;
                    if (hasCurrentlyPlaying) {
                        if (CloudMusic.player.isPausing())
                            CloudMusic.player.unpause();
                        else
                            CloudMusic.player.pause();
                    }
                    return true;
                });

        IconWidget prev = new IconWidget("H", FontManager.icon30, 0, 0, 20, 20);

        this.addChild(prev);

        prev
                .setOnClickCallback((x, y, i) -> {
                    if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null)
                        CloudMusic.prev();

                    return true;
                })
                .setBeforeRenderCallback(() -> prev
                        .center()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setPosition(prev.getRelativeX() - 20 - prev.getWidth() * .5, prev.getRelativeY() + buttonsYOffset));
        IconWidget next = new IconWidget("E", FontManager.icon30, 0, 0, 20, 20);
        this.addChild(next);

        next
                .setOnClickCallback((x, y, i) -> {
                    if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null)
                        CloudMusic.next();

                    return true;
                })
                .setBeforeRenderCallback(() -> next
                        .center()
                        .setPosition(next.getRelativeX() + next.getWidth() * .5 + 20, next.getRelativeY() + buttonsYOffset)
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));
        // 播放模式与核心控件并列：顺序播放 → 随机播放 → 单曲循环。
        IconWidget playMode = new IconWidget(CloudMusic.playMode.getIcon(), FontManager.icon30, 0, 0, 20, 20);
        this.addChild(playMode);

        playMode
                .setBeforeRenderCallback(() -> {
                    CloudMusic.PlayMode mode = CloudMusic.playMode;
                    boolean personalFm = CloudMusic.isPersonalFmActive();
                    playMode
                            .center()
                            .setIcon(personalFm ? "F" : mode.getIcon())
                            .setPosition(next.getRelativeX() + next.getWidth() + 10, next.getRelativeY())
                            .setClickable(!personalFm)
                            .setAlpha(ControlsBar.this.getAlpha() * (personalFm ? .35f : 1.0f))
                            .setColor(personalFm || mode == CloudMusic.PlayMode.Sequential
                                    ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                })
                .setOnClickCallback((x, y, mouseButton) -> {
                    if (mouseButton == 0) {
                        if (!CloudMusic.isPersonalFmActive()) CloudMusic.cyclePlayMode();
                    }
                    return true;
                });

        // Explicit personal-FM action. It is hidden entirely outside the FM session,
        // so normal playlists never expose nor invoke the radio-trash endpoint.
        RoundedButtonWidget fmSkip = new RoundedButtonWidget("换一首", FontManager.pf12bold);
        this.addChild(fmSkip);
        fmSkip.setShouldOverrideMouseCursor(true);
        fmSkip.setBeforeRenderCallback(() -> {
            boolean active = CloudMusic.isPersonalFmActive();
            fmSkip.setHidden(!active);
            fmSkip.setClickable(active && !PersonalFmManager.isLoading());
            fmSkip.setBounds(44, 17);
            fmSkip.setPosition(playMode.getRelativeX() + playMode.getWidth() + 10, playMode.getRelativeY() + 1);
            fmSkip.setRadius(4);
            fmSkip.setAlpha(ControlsBar.this.getAlpha() * (PersonalFmManager.isLoading() ? .50f : 1.0f));
            fmSkip.setColor(fmSkip.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            fmSkip.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        fmSkip.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0 || !CloudMusic.isPersonalFmActive()) return mouseButton == 0;
            PersonalFmManager.skipCurrentAndRequestNext();
            return true;
        });



        RoundedRectWidget progressBarBg = new RoundedRectWidget() {

            boolean prevMouse = false;

            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);

                if (prevMouse && !Mouse.isButtonDown(0))
                    prevMouse = false;

                if (this.testHovered(mouseX, mouseY, 1) && Mouse.isButtonDown(0) && !prevMouse) {
                    prevMouse = true;
                    double xDelta = Math.max(0, Math.min(this.getWidth(), (mouseX - this.getX())));
                    double percent = xDelta / this.getWidth();

                    if (CloudMusic.player != null) {
                        float total = CloudMusic.player.getTotalTimeMillis();
                        if (total > 0.0f) {
                            float progress = (float) (percent * total);
                            CloudMusic.player.setPlaybackTime(progress);
                            // Seek 后立即读取真实 positionMs，用实际音频时钟同步歌词/进度
                            float actual = CloudMusic.player.getCurrentTimeMillis();
                            MusicLyricsWidget.resetProgress(actual);
                            MusicLyricsPanel.resetProgress(actual);
                        }
                    }
                }
            }
        };

        this.addChild(progressBarBg);

        progressBarBg
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER))
                .setRadius(1)
                .setBounds(135, 3)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> progressBarBg
                        .center()
                        .setPosition(progressBarBg.getRelativeX(), progressBarBg.getRelativeY() + 8));

        RoundedRectWidget progressBar = new RoundedRectWidget();

        progressBarBg.addChild(progressBar);
        progressBar
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setWidth(0)
                .setClickable(false)
                .setBeforeRenderCallback(() -> {
                    progressBar.setMargin(0);

                    AudioPlayer player = CloudMusic.player;
                    if (player == null)
                        return;

                    float total = player.getTotalTimeMillis();
                    if (total <= 0.0f) {
                        progressBar.setWidth(0).setRadius(0);
                        return;
                    }

                    float perc = Math.max(0.0f,
                            Math.min(1.0f, player.getCurrentTimeMillis() / total));
                    progressBar
                            .setWidth(perc * progressBarBg.getWidth())
                            .setRadius(perc);
                });

        LabelWidget lblCurTime = new LabelWidget(
                () -> {
                    if (CloudMusic.player == null)
                        return "00:00";
                    return formatDuration(CloudMusic.player.getCurrentTimeMillis());
                },
                FontManager.pf12
        );
        this.addChild(lblCurTime);

        lblCurTime
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblCurTime
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(
                                progressBarBg.getRelativeX() - lblCurTime.getWidth() - 4,
                                progressBarBg.getRelativeY() + progressBarBg.getHeight() * .5 - lblCurTime.getHeight() * .5
                        ));

        LabelWidget lblRemainingTime = new LabelWidget(
                () -> {
                    if (CloudMusic.player == null)
                        return "00:00";
                    float total = CloudMusic.player.getTotalTimeMillis();
                    if (total <= 0.0f)
                        return "00:00";
                    return "-" + formatDuration(Math.max(0.0f,
                            total - CloudMusic.player.getCurrentTimeMillis()));
                },
                FontManager.pf12
        );
        this.addChild(lblRemainingTime);

        lblRemainingTime
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblRemainingTime
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(progressBarBg.getRelativeX() + progressBarBg.getWidth() + 4, lblCurTime.getRelativeY()));

        LabelWidget lblMusicName = new LabelWidget(() -> CloudMusic.currentlyPlaying == null ? "未在播放" : CloudMusic.currentlyPlaying.getName(), FontManager.pf14bold);
        this.addChild(lblMusicName);

        lblMusicName
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblMusicName
                        .centerVertically()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setMaxWidth(lblCurTime.getRelativeX() - lblMusicName.getRelativeX() - 4)
                        .setPosition(
                                playingCover.getRelativeX() + playingCover.getWidth() + 4,
                                lblMusicName.getRelativeY() - lblMusicName.getHeight() * .5 - 2
                        ));

        LabelWidget lblMusicArtist = new LabelWidget(
                () -> {
                    if (CloudMusic.currentlyPlaying == null)
                        return "无";
                    return CloudMusic.currentlyPlaying.getArtistsName() + " - " + CloudMusic.currentlyPlaying.getAlbum().getName();
                },
                FontManager.pf14bold
        );
        this.addChild(lblMusicArtist);

        lblMusicArtist
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblMusicArtist
                        .centerVertically()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setMaxWidth(lblCurTime.getRelativeX() - lblMusicArtist.getRelativeX() - 4)
                        .setPosition(
                                playingCover.getRelativeX() + playingCover.getWidth() + 4,
                                lblMusicArtist.getRelativeY() + lblMusicArtist.getHeight() * .5 + 2
                        ));

        // Right-aligned compact volume strip. It delegates all visual and input behavior to the
        // full-screen control implementation, so both surfaces remain visually synchronized.
        final VolumeControl compactVolumeControl = new VolumeControl();
        RoundedRectWidget compactVolumeWidget = new RoundedRectWidget() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                compactVolumeControl.render(mouseX, mouseY, this.getX(),
                        this.getY() + this.getHeight() * .5, this.getWidth(), this.getAlpha());
            }
        };
        this.addChild(compactVolumeWidget);
        compactVolumeWidget
                .setClickable(false)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    double controlWidth = Math.max(74.0,
                            Math.min(122.0, compactVolumeWidget.getParentWidth() * .18));
                    compactVolumeWidget
                            .setBounds(controlWidth, 20.0)
                            .setPosition(compactVolumeWidget.getParentWidth() - controlWidth - 8.0,
                                    Math.max(1.0, (compactVolumeWidget.getParentHeight() - 20.0) * .5))
                            .setAlpha(ControlsBar.this.getAlpha());
                });

        // Compact quality selector.  The available provider tiers are exposed in an upward
        // pop-up so a user can select a known target instead of repeatedly cycling the button.
        // Keeping all controls as direct children of ControlsBar also makes the pop-up render
        // above the compact volume control and prevents it from being clipped by the selector.
        final Quality[] selectableQualities = Quality.values();
        final double qualityButtonHeight = 20.0;
        final double qualityOptionHeight = 18.0;
        final double qualityMenuPadding = 3.0;
        final boolean[] qualityMenuOpen = {false};
        final RoundedRectWidget[] qualityMenuBackground = new RoundedRectWidget[1];
        final RoundedRectWidget[] qualityOptions = new RoundedRectWidget[selectableQualities.length];
        RoundedRectWidget qualitySelector = new RoundedRectWidget();
        this.addChild(qualitySelector);
        qualitySelector
                .setClickable(true)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    // Dedicated footer slot: immediately left of the volume control, with a
                    // fixed visual gap matching the player layout.  Do not let a transient
                    // layout value hide this primary action.
                    double parentWidth = qualitySelector.getParentWidth();
                    double volumeWidth = Math.max(74.0, Math.min(122.0, parentWidth * .18));
                    double volumeX = compactVolumeWidget.getRelativeX();
                    if (volumeX <= 0.0 || volumeX >= parentWidth) {
                        volumeX = parentWidth - volumeWidth - 8.0;
                    }
                    double selectorGap = Math.min(38.0, Math.max(14.0, parentWidth * .045));
                    String selectorLabel = "音质 · " + formatQuality(CloudMusic.quality) + (qualityMenuOpen[0] ? " ︿" : " ﹀");
                    double desiredWidth = FontManager.pf12bold.getStringWidthD(selectorLabel) + 14.0;
                    double selectorWidth = Math.max(72.0, Math.min(112.0, desiredWidth));
                    double selectorX = volumeX - selectorGap - selectorWidth;

                    // On a very narrow player, shrink only enough to remain inside the footer;
                    // the label itself trims safely instead of the whole quality action vanishing.
                    if (selectorX < 6.0) {
                        selectorWidth = Math.max(34.0, volumeX - selectorGap - 6.0);
                        selectorX = Math.max(6.0, volumeX - selectorGap - selectorWidth);
                    }

                    qualitySelector
                            .setHidden(false)
                            .setClickable(true)
                            .setBounds(selectorWidth, qualityButtonHeight)
                            .setRadius(5.0)
                            .setColor(qualitySelector.isHovering()
                                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                            .setAlpha(ControlsBar.this.getAlpha() * .94f)
                            .setPosition(selectorX,
                                    Math.max(1.0, (qualitySelector.getParentHeight() - qualityButtonHeight) * .5));
                })
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (mouseButton == 0) {
                        boolean open = !qualityMenuOpen[0];
                        qualityMenuOpen[0] = open;
                        qualityMenuBackground[0].setHidden(!open);
                        for (RoundedRectWidget qualityOption : qualityOptions) {
                            qualityOption.setHidden(!open);
                        }
                    }
                    return true;
                });

        LabelWidget qualityText = new LabelWidget(() -> "音质 · " + formatQuality(CloudMusic.quality) + (qualityMenuOpen[0] ? " ︿" : " ﹀"), FontManager.pf12bold);
        qualitySelector.addChild(qualityText);
        qualityText.setClickable(false);
        qualityText.setBeforeRenderCallback(() -> qualityText
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setMaxWidth(Math.max(0.0, qualitySelector.getWidth() - 14.0))
                .setPosition(7.0, Math.max(2.0, (qualityButtonHeight - qualityText.getHeight()) * .5)));

        // The menu is declared after the selector, therefore it is rendered and hit-tested on
        // top of the bottom bar.  It grows upward so it never covers the compact controls.
        qualityMenuBackground[0] = new RoundedRectWidget();
        this.addChild(qualityMenuBackground[0]);
        qualityMenuBackground[0]
                .setClickable(false)
                .setHidden(true)
                .setBeforeRenderCallback(() -> {
                    double menuHeight = selectableQualities.length * qualityOptionHeight + qualityMenuPadding * 2.0;
                    qualityMenuBackground[0]
                            .setBounds(qualitySelector.getWidth(), menuHeight)
                            .setPosition(qualitySelector.getRelativeX(), qualitySelector.getRelativeY() - menuHeight - 4.0)
                            .setRadius(6.0)
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                            .setAlpha(ControlsBar.this.getAlpha() * .98f);
                });

        for (int index = 0; index < selectableQualities.length; index++) {
            final Quality option = selectableQualities[index];
            final int optionIndex = index;
            qualityOptions[index] = new RoundedRectWidget();
            RoundedRectWidget qualityOption = qualityOptions[index];
            this.addChild(qualityOption);
            qualityOption
                    .setShouldOverrideMouseCursor(true)
                    .setHidden(true)
                    .setBeforeRenderCallback(() -> {
                        boolean selected = option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality);
                        qualityOption
                                .setBounds(Math.max(0.0, qualitySelector.getWidth() - qualityMenuPadding * 2.0), qualityOptionHeight)
                                .setPosition(qualitySelector.getRelativeX() + qualityMenuPadding,
                                        qualityMenuBackground[0].getRelativeY() + qualityMenuPadding + optionIndex * qualityOptionHeight)
                                .setRadius(4.0)
                                .setColor(qualityOption.isHovering()
                                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                        : (selected ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT) : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)))
                                .setAlpha(ControlsBar.this.getAlpha() * (selected ? .72f : .98f));
                    })
                    .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                        if (mouseButton != 0) return true;
                        CloudMusic.quality = option;
                        NCMPlayerConfig.setAudioQuality(option);
                        qualityMenuOpen[0] = false;
                        qualityMenuBackground[0].setHidden(true);
                        for (RoundedRectWidget menuOption : qualityOptions) {
                            menuOption.setHidden(true);
                        }
                        DownloadDynamicIsland.showPlaybackQuality("已切换 " + formatQuality(option), "下次播放生效");
                        return true;
                    });

            LabelWidget optionText = new LabelWidget(() -> option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality)
                    ? "✓  " + formatQuality(option)
                    : "   " + formatQuality(option), FontManager.pf12bold);
            qualityOption.addChild(optionText);
            optionText.setClickable(false);
            optionText.setBeforeRenderCallback(() -> optionText
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                    .setPosition(5.0, Math.max(1.0, (qualityOptionHeight - optionText.getHeight()) * .5)));
        }
    }

    private static String formatQuality(Quality quality) {
        if (quality == null) return "无损";
        switch (quality) {
            case STANDARD: return "标准";
            case HIGHER: return "高品质";
            case EXHIGH: return "极高";
            case LOSSLESS: return "无损";
            case HIRES: return "Hi-Res";
            case JYEFFECT: return "高清环绕";
            case SKY: return "沉浸音质";
            case JYMASTER: return "母带";
            default: return quality.getQuality();
        }
    }
    private String formatDuration(float totalMillis) {
        int totalSeconds = (int) (totalMillis / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        String result = "";

        if (hours > 0) {
            result += (hours < 10 ? "0" : "") + hours + ":";
        }

        result += (minutes < 10 ? "0" : "") + minutes + ":";
        result += (seconds < 10 ? "0" : "") + seconds;

        return result;
    }
}
