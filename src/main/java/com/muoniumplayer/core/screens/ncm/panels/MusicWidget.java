package com.muoniumplayer.core.screens.ncm.panels;

import today.opai.api.enums.EnumChatColor;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.widgets.IconWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.*;

/**
 * @author IzumiiKonata
 * Date: 2025/10/17 20:40
 */
public class MusicWidget extends RoundedRectWidget {

    private static final long ENTRANCE_DURATION_MILLIS = 280L;

    public PlayList playList;
    public Music music;
    boolean coverLoaded = false;
    private long entranceStartedAt = -1L;
    private long entranceDelayMillis;
    private boolean entranceAnimationFinished = true;

    public MusicWidget(Music music, PlayList playList, int index) {
        super(0, 0, 0, 30);
        this.music = music;
        this.playList = playList;

        RoundedRectWidget rrHoverIndicator = new RoundedRectWidget();
        this.addChild(rrHoverIndicator);
        rrHoverIndicator
                .setAlpha(0f)
                .setClickable(false);
        rrHoverIndicator.setBeforeRenderCallback(() -> rrHoverIndicator
                .setMargin(0)
                .setRadius(this.getRadius())
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)));

        RoundedRectWidget rrPlayingIndicator = new RoundedRectWidget();
        this.addChild(rrPlayingIndicator);
        rrPlayingIndicator
                .setAlpha(0f)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setClickable(false);
        if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music)) {
            rrPlayingIndicator.setAlpha(1f);
        }
        rrPlayingIndicator.setBeforeRenderCallback(() -> rrPlayingIndicator
                .setMargin(0)
                .setRadius(this.getRadius()));

        this.setBeforeRenderCallback(() -> {

            // 只在这个 music 被渲染的时候才加载封面
            if (!coverLoaded) {
                coverLoaded = true;
                this.loadCover();
            }

            this.setBounds(this.getParentWidth(), 30);
            this.setColor(NCMScreen.getColor(index % 2 == 0 ? NCMScreen.ColorType.ELEMENT_BACKGROUND : NCMScreen.ColorType.GENERIC_BACKGROUND));
            this.updateEntranceAnimation();

            if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music)) {
//                this.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                rrPlayingIndicator.setAlpha(Interpolations.interpolate(rrPlayingIndicator.getWidgetAlpha(), .9f, .4f));
                rrPlayingIndicator.setHidden(false);
            } else if (this.isHovering()) {
//                this.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                rrHoverIndicator.setAlpha(Interpolations.interpolate(rrHoverIndicator.getWidgetAlpha(), 1, .3f));
                rrHoverIndicator.setHidden(false);
            } else {
                rrPlayingIndicator.setAlpha(Interpolations.interpolate(rrPlayingIndicator.getWidgetAlpha(), 0, .4f));
                rrHoverIndicator.setAlpha(Interpolations.interpolate(rrHoverIndicator.getWidgetAlpha(), 0, .3f));

                if (rrPlayingIndicator.getWidgetAlpha() <= .05f)
                    rrPlayingIndicator.setHidden(true);

                if (rrHoverIndicator.getWidgetAlpha() <= .05f)
                    rrHoverIndicator.setHidden(true);
            }

            this.setRadius(2);
        });

        this.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                CloudMusic.currentPlaylistContext = playList.isSearchMode() ? null : playList;
                if (playList.isPersonalFm()) CloudMusic.playFm(playList.getMusics(), index);
                else CloudMusic.play(playList.getMusics(), index);
            }

            return true;
        });

        RoundedImageWidget cover = new RoundedImageWidget(this.music.getSmallCoverLocation(), 0, 0, 0, 0);
        this.addChild(cover);
        cover.fadeIn();
        cover.setLinearFilter(true);
        cover.setBeforeRenderCallback(() -> {
            cover.setRadius(2);
            cover.setBounds(24, 24);
            cover.centerVertically();
            cover.setPosition(30, cover.getRelativeY());
        });
        cover.setClickable(false);

        LabelWidget lblMusicIndex = new LabelWidget(String.valueOf(index + 1), FontManager.pf14bold);
        this.addChild(lblMusicIndex);

        lblMusicIndex.setBeforeRenderCallback(() -> {
            if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music))
                lblMusicIndex.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            else
                lblMusicIndex.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblMusicIndex.centerVertically();
            lblMusicIndex.setPosition(cover.getRelativeX() - 4 - lblMusicIndex.getWidth(), lblMusicIndex.getRelativeY());
        });

        lblMusicIndex.setClickable(false);

        boolean musicDirty = music.isDirty();
        double dirtyIndicatorSize = 8;
        boolean digitalAlbumTrack = music.isDigitalAlbumTrack();
        // A digital-album purchase is a different entitlement from a VIP-only stream;
        // show one clear badge instead of a redundant "VIP + 专辑" pair.
        boolean vipRestricted = music.hasVipRestriction() && !digitalAlbumTrack;
        boolean cloudSong = music.isCloudSong();
        String highestQuality = music.getHighestQualityLabel();
        boolean showHighestQuality = highestQuality != null && !highestQuality.isEmpty();
        double accessBadgeReserve = (vipRestricted ? 24.0 : 0.0) + (digitalAlbumTrack ? 28.0 : 0.0)
                + (cloudSong ? 28.0 : 0.0) + (showHighestQuality ? 38.0 : 0.0);

        String translatedNames = music.getTranslatedNames();

        LabelWidget lblMusicName = new LabelWidget(music.getName() + (translatedNames.isEmpty() ? "" : EnumChatColor.GRAY + " (" + translatedNames + ")"), FontManager.pf14bold);
        this.addChild(lblMusicName);

        lblMusicName
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL)
                .setBeforeRenderCallback(() -> {
                    lblMusicName.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                    lblMusicName.centerVertically();
                    lblMusicName.setPosition(cover.getRelativeX() + cover.getWidth() + 4, lblMusicName.getRelativeY() - lblMusicName.getHeight() * .5 - 2);
                    // Reserve space for duration/actions and licensing badges so no badge can overlap a trimmed title.
                    lblMusicName.setMaxWidth(Math.max(1.0, this.getWidth() - (cover.getRelativeX() + cover.getWidth() + 4
                            + (music.isNetease() ? 80 : 40) + (musicDirty ? (dirtyIndicatorSize + 4) : 0)
                            + accessBadgeReserve)));
                });
        lblMusicName.setClickable(false);

        double nextAccessBadgeX = 0.0;
        if (vipRestricted) {
            RoundedRectWidget vipBadge = new RoundedRectWidget(0, 0, 21, 10);
            this.addChild(vipBadge);
            vipBadge.setClickable(false);
            vipBadge.setBeforeRenderCallback(() -> {
                vipBadge.setRadius(2.5);
                vipBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                vipBadge.setAlpha(.92f);
                vipBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - vipBadge.getHeight() * .5);
            });
            LabelWidget vipText = new LabelWidget("VIP", FontManager.pf12bold);
            vipBadge.addChild(vipText);
            vipText.setClickable(false);
            vipText.setBeforeRenderCallback(() -> {
                vipText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                vipText.center();
            });
            nextAccessBadgeX = 24.0;
        }

        if (digitalAlbumTrack) {
            final double albumBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget albumBadge = new RoundedRectWidget(0, 0, 25, 10);
            this.addChild(albumBadge);
            albumBadge.setClickable(false);
            albumBadge.setBeforeRenderCallback(() -> {
                albumBadge.setRadius(2.5);
                albumBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                albumBadge.setAlpha(.95f);
                albumBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + albumBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - albumBadge.getHeight() * .5);
            });
            LabelWidget albumText = new LabelWidget("专辑", FontManager.pf12bold);
            albumBadge.addChild(albumText);
            albumText.setClickable(false);
            albumText.setBeforeRenderCallback(() -> {
                albumText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                albumText.center();
            });
        }

        // Keep the title badges in one advancing row: VIP / digital album / cloud drive / highest tier.
        nextAccessBadgeX += digitalAlbumTrack ? 28.0 : 0.0;
        if (cloudSong) {
            final double cloudBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget cloudBadge = new RoundedRectWidget(0, 0, 25, 10);
            this.addChild(cloudBadge);
            cloudBadge.setClickable(false);
            cloudBadge.setBeforeRenderCallback(() -> {
                cloudBadge.setRadius(2.5);
                cloudBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                cloudBadge.setAlpha(.95f);
                cloudBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + cloudBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - cloudBadge.getHeight() * .5);
            });
            LabelWidget cloudText = new LabelWidget("网盘", FontManager.pf12bold);
            cloudBadge.addChild(cloudText);
            cloudText.setClickable(false);
            cloudText.setBeforeRenderCallback(() -> {
                cloudText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                cloudText.center();
            });
            nextAccessBadgeX += 28.0;
        }

        if (showHighestQuality) {
            final double qualityBadgeOffset = nextAccessBadgeX;
            final double qualityBadgeWidth = "Hi-Res".equals(highestQuality) ? 34.0 : 27.0;
            RoundedRectWidget qualityBadge = new RoundedRectWidget(0, 0, qualityBadgeWidth, 10);
            this.addChild(qualityBadge);
            qualityBadge.setClickable(false);
            qualityBadge.setBeforeRenderCallback(() -> {
                qualityBadge.setRadius(2.5);
                qualityBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                qualityBadge.setAlpha(.82f);
                qualityBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + qualityBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - qualityBadge.getHeight() * .5);
            });
            LabelWidget qualityText = new LabelWidget(highestQuality, FontManager.pf12bold);
            qualityBadge.addChild(qualityText);
            qualityText.setClickable(false);
            qualityText.setBeforeRenderCallback(() -> {
                qualityText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                qualityText.center();
            });
            nextAccessBadgeX += qualityBadgeWidth + 3.0;
        }

        final double dirtyBadgeOffset = nextAccessBadgeX;
        if (musicDirty) {
            RoundedRectWidget dirtyIndicator = new RoundedRectWidget(0, 0, dirtyIndicatorSize, dirtyIndicatorSize);
            this.addChild(dirtyIndicator);
            dirtyIndicator
                    .setRadius(1.5)
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));

            dirtyIndicator.setBeforeRenderCallback(() -> {
//                dirtyIndicator.centerVertically();
                dirtyIndicator.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 2 + dirtyBadgeOffset, lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - dirtyIndicatorSize * .5);
            });

            dirtyIndicator.setClickable(false);

            LabelWidget lblDirty = new LabelWidget("E", FontManager.pf12bold);
            dirtyIndicator.addChild(lblDirty);
            lblDirty.setBeforeRenderCallback(() -> {
                lblDirty.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lblDirty.center();
            });
        }

        String albumName = music.getAlbum() == null || music.getAlbum().getName() == null
                ? "未知专辑" : music.getAlbum().getName();
        LabelWidget lblMusicArtist = new LabelWidget(music.getArtistsName() + " - " + albumName, FontManager.pf14);
        this.addChild(lblMusicArtist);

        lblMusicArtist
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL)
                .setBeforeRenderCallback(() -> {
                    if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music))
                        lblMusicArtist.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                    else
                        lblMusicArtist.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                    lblMusicArtist.centerVertically();
                    lblMusicArtist.setPosition(cover.getRelativeX() + cover.getWidth() + 4, lblMusicArtist.getRelativeY() + lblMusicArtist.getHeight() * .5 + 2);
                    lblMusicArtist.setMaxWidth(this.getWidth() - (cover.getRelativeX() + cover.getWidth() + 4 + (music.isNetease() ? 80 : 40)));
                });

        lblMusicArtist.setClickable(false);

        LabelWidget lblMusicDuration = new LabelWidget(formatDuration(music.getDuration()), FontManager.pf14bold);
        this.addChild(lblMusicDuration);
        lblMusicDuration.setBeforeRenderCallback(() -> {
            if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music))
                lblMusicDuration.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            else
                lblMusicDuration.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblMusicDuration.centerVertically();
            lblMusicDuration.setPosition(this.getWidth() - 8 - lblMusicDuration.getWidth(), lblMusicDuration.getRelativeY());
        });
        lblMusicDuration.setClickable(false);

        // ===== 收藏：歌曲喜欢/取消喜欢（★/☆）+ 加入歌单（+）=====
        IconWidget btnLike = new IconWidget("☆", FontManager.pf16bold, 0, 0, 20, 20);
        this.addChild(btnLike);
        btnLike.setShouldOverrideMouseCursor(true);
        btnLike.setHidden(!music.isNetease());
        btnLike.setClickable(music.isNetease());
        btnLike.setBeforeRenderCallback(() -> {
            boolean liked = CloudMusic.likeList != null && CloudMusic.likeList.contains(music.getId());
            btnLike.setIcon(liked ? "★" : "☆");
            btnLike.setColor(liked ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            btnLike.centerVertically();
            btnLike.setPosition(lblMusicDuration.getRelativeX() - 4 - btnLike.getWidth(), btnLike.getRelativeY());
        });
        btnLike.setOnClickCallback((x, y, button) -> {
            if (button != 0 || !music.isNetease())
                return false;

            boolean liked = CloudMusic.likeList != null && CloudMusic.likeList.contains(music.getId());
            if (!liked) {
                if (CloudMusic.likeList != null && !CloudMusic.likeList.contains(music.getId())) {
                    CloudMusic.likeList.add(music.getId());
                }
                MultiThreadingUtil.runAsync(() -> music.setLike(true));
                return true;
            }

            NCMScreen.getInstance().openConfirmation(
                    "取消收藏歌曲？",
                    "取消后，这首歌曲将从“我喜欢的音乐”中移除。",
                    "取消收藏",
                    () -> {
                        if (CloudMusic.likeList != null) {
                            CloudMusic.likeList.remove(music.getId());
                        }
                        MultiThreadingUtil.runAsync(() -> music.setLike(false));
                    }
            );
            return true;
        });

        IconWidget btnAddToPlaylist = new IconWidget("+", FontManager.pf16bold, 0, 0, 20, 20);
        this.addChild(btnAddToPlaylist);
        btnAddToPlaylist.setShouldOverrideMouseCursor(true);
        btnAddToPlaylist.setHidden(!music.isNetease());
        btnAddToPlaylist.setClickable(music.isNetease());
        btnAddToPlaylist.setBeforeRenderCallback(() -> {
            btnAddToPlaylist.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            btnAddToPlaylist.centerVertically();
            btnAddToPlaylist.setPosition(btnLike.getRelativeX() - 4 - btnAddToPlaylist.getWidth(), btnAddToPlaylist.getRelativeY());
        });
        btnAddToPlaylist.setOnClickCallback((x, y, button) -> {
            if (button != 0 || !music.isNetease())
                return false;
            NCMScreen.getInstance().openAddToPlaylist(music);
            return true;
        });
    }

    /**
     * Gives freshly fetched tracks a short, staggered opacity entrance without
     * moving their real layout bounds (so scrolling and hit-testing stay stable).
     */
    public MusicWidget setEntranceAnimation(long startedAt, long delayMillis) {
        this.entranceStartedAt = startedAt;
        this.entranceDelayMillis = Math.max(0L, delayMillis);
        this.entranceAnimationFinished = false;
        this.setAlpha(0f);
        return this;
    }

    private void updateEntranceAnimation() {
        if (entranceAnimationFinished) {
            return;
        }

        long elapsed = System.currentTimeMillis() - entranceStartedAt - entranceDelayMillis;
        if (elapsed <= 0L) {
            this.setAlpha(0f);
            return;
        }

        float progress = Math.min(1f, elapsed / (float) ENTRANCE_DURATION_MILLIS);
        // Cubic ease-out makes the fade settle gently instead of appearing linear.
        float easedProgress = 1f - (float) Math.pow(1f - progress, 3f);
        this.setAlpha(easedProgress);

        if (progress >= 1f) {
            this.setAlpha(1f);
            this.entranceAnimationFinished = true;
        }
    }
    private String formatDuration(long totalMillis) {
        long totalSeconds = totalMillis / 1000;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();

        if (hours > 0) {
            sb.append(String.format("%02d:", hours));
        }

        sb.append(String.format("%02d:", minutes));
        sb.append(String.format("%02d", seconds));

        return sb.toString();
    }

    private void loadCover() {

        TextureManager textureManager = TextureManager.getInstance();
        Location coverLoc = this.music.getSmallCoverLocation();
        if (textureManager.getTexture(coverLoc) != null)
            return;

        Textures.downloadTextureAndLoadAsync(music.getCoverUrl(64), coverLoc);
    }

}
