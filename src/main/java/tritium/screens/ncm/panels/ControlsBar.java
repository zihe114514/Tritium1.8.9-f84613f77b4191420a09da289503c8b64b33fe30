package tritium.screens.ncm.panels;

import org.lwjgl.input.Mouse;
import tritium.management.FontManager;
import tritium.ncm.music.AudioPlayer;
import tritium.ncm.music.CloudMusic;
import tritium.screens.ncm.NCMPlayerConfig;
import tritium.rendering.DownloadDynamicIsland;
import tritium.ncm.music.Quality;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.ui.widgets.*;
import tritium.screens.ncm.MusicLyricsPanel;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.NCMTheme;
import tritium.screens.ncm.VolumeControl;
import tritium.widget.impl.MusicLyricsWidget;


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
                    playMode
                            .center()
                            .setIcon(mode.getIcon())
                            .setPosition(next.getRelativeX() + next.getWidth() + 10, next.getRelativeY())
                            .setColor(mode == CloudMusic.PlayMode.Sequential
                                    ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                })
                .setOnClickCallback((x, y, mouseButton) -> {
                    if (mouseButton == 0) {
                        CloudMusic.cyclePlayMode();
                    }
                    return true;
                });

        // Heart mode is a bottom-player control rather than a playlist-header text button.
        // It remains visible but muted when the current queue did not originate from a NetEase playlist.
        IconWidget intelligenceMode = new IconWidget("♥", FontManager.pf20bold, 0, 0, 20, 20);
        this.addChild(intelligenceMode);
        intelligenceMode.setShouldOverrideMouseCursor(true);
        intelligenceMode.setBeforeRenderCallback(() -> {
            PlayList context = CloudMusic.currentPlaylistContext;
            boolean available = context != null && context.getPlatform() == tritium.ncm.music.MusicPlatform.NETEASE
                    && context.getId() > 0 && CloudMusic.currentlyPlaying != null
                    && CloudMusic.currentlyPlaying.isNetease();
            intelligenceMode.setClickable(available);
            intelligenceMode.setBounds(20, 20);
            intelligenceMode.setPosition(playMode.getRelativeX() + playMode.getWidth() + 14, playMode.getRelativeY());
            intelligenceMode.setAlpha(ControlsBar.this.getAlpha() * (available ? 1.0f : .30f));
            intelligenceMode.setColor(available && intelligenceMode.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : (available ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)));
        });
        intelligenceMode.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            PlayList context = CloudMusic.currentPlaylistContext;
            if (context != null && CloudMusic.currentlyPlaying != null) {
                NeteaseDiscoveryPanel.openIntelligence(context, CloudMusic.currentlyPlaying);
                return true;
            }
            return false;
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

        // Bottom-right compact volume strip. It delegates all visual and input behavior to the
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
                                    Math.max(1.0, compactVolumeWidget.getParentHeight() - 22.0))
                            .setAlpha(ControlsBar.this.getAlpha());
                });

        // Compact quality switch: each primary click moves to the next provider tier.  The
        // button stays a single row and deliberately has no expanding menu or transition.
        final Quality[] selectableQualities = Quality.values();
        final double qualityButtonHeight = 20.0;
        RoundedRectWidget qualitySelector = new RoundedRectWidget();
        this.addChild(qualitySelector);
        qualitySelector
                .setClickable(true)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    double selectorWidth = 94.0;
                    double volumeWidth = Math.max(74.0, Math.min(122.0, qualitySelector.getParentWidth() * .18));
                    qualitySelector
                            .setBounds(selectorWidth, qualityButtonHeight)
                            .setRadius(5.0)
                            .setColor(qualitySelector.isHovering()
                                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                            .setAlpha(ControlsBar.this.getAlpha() * .94f)
                            .setPosition(Math.max(6.0, qualitySelector.getParentWidth() - volumeWidth - selectorWidth - 14.0),
                                    Math.max(1.0, qualitySelector.getParentHeight() - qualityButtonHeight - 2.0));
                })
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (mouseButton != 0) {
                        return true;
                    }
                    Quality current = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
                    int currentIndex = 0;
                    for (int index = 0; index < selectableQualities.length; index++) {
                        if (selectableQualities[index] == current) {
                            currentIndex = index;
                            break;
                        }
                    }
                    Quality selected = selectableQualities[(currentIndex + 1) % selectableQualities.length];
                    CloudMusic.quality = selected;
                    NCMPlayerConfig.setAudioQuality(selected);
                    DownloadDynamicIsland.showPlaybackQuality("已切换 " + formatQuality(selected), "下次播放生效");
                    return true;
                });

        LabelWidget qualityText = new LabelWidget(() -> "音质 · " + formatQuality(CloudMusic.quality), FontManager.pf12bold);
        qualitySelector.addChild(qualityText);
        qualityText.setClickable(false);
        qualityText.setBeforeRenderCallback(() -> qualityText
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(7.0, Math.max(2.0, (qualityButtonHeight - qualityText.getHeight()) * .5)));
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
