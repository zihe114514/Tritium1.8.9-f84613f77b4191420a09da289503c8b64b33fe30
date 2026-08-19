package com.muoniumplayer.core.rendering;

import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;

import java.awt.Color;

/**
 * Global, screen-independent download activity island.
 *
 * <p>This class owns both the small thread-safe download snapshot and its presentation. Forge
 * renders it as a global overlay above gameplay and every GuiScreen, so it does not depend on
 * the full-screen player or inherit the player's layout/scale animation.</p>
 */
public final class DownloadDynamicIsland implements SharedConstants, SharedRenderingConstants {

    private static final DownloadDynamicIsland INSTANCE = new DownloadDynamicIsland();
    private static final double COMPACT_WIDTH = 40.0;
    private static final double COMPACT_HEIGHT = 16.0;
    private static final double TOP_MARGIN = 6.0;
    private static final double MIN_AUTO_TEXT_SCALE = .62;
    private static final double MAX_TEXT_SCALE = 1.18;
    private static final double CONTENT_CHANGE_SLIDE = 4.5;

    /** Modern island silhouettes inspired by pill, glass, compact status and card surfaces. */
    public enum DynamicIslandStyle {
        PILL("经典胶囊"),
        GLASS("通透玻璃"),
        COMPACT("紧凑状态"),
        CARD("浮层卡片"),
        SYSTEM_CARD("系统通知"),
        MUSIC_FOCUS("音乐聚焦");

        private final String displayName;

        DynamicIslandStyle(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static DynamicIslandStyle getStyle() {
        DynamicIslandStyle[] styles = DynamicIslandStyle.values();
        return styles[HudConfig.clampInt(HudConfig.dynamicIslandStyle, 0, styles.length - 1)];
    }

    public static String getStyleName() {
        return getStyle().getDisplayName();
    }

    /** Cycles only the island presentation; download state and notices remain untouched. */
    public static void cycleStyle() {
        DynamicIslandStyle[] styles = DynamicIslandStyle.values();
        HudConfig.dynamicIslandStyle = (HudConfig.clampInt(HudConfig.dynamicIslandStyle, 0, styles.length - 1) + 1) % styles.length;
        HudConfig.save();
        publishNotice(IslandNoticeType.STYLE, "灵动岛样式", getStyleName());
    }

    private static volatile boolean downloading;
    private static volatile double downloadProgress;
    private static volatile String downloadSpeed = "0 b/s";
    private static volatile long downloadStartedAt;
    private static volatile long downloadCompletedAt;
    private static volatile boolean transcoding;
    private static volatile double transcodeProgress;

    private static volatile IslandNoticeType noticeType = IslandNoticeType.NONE;
    private static volatile String noticeTitle = "";
    private static volatile String noticeValue = "";
    private static volatile long noticeShownAt;
    private static volatile long noticeRevision;
    private static volatile boolean noticePersistent;

    private float visibility;
    private double expansion;
    private float contentAlpha;
    private double animatedProgress;
    private float successMorph;
    private double spinnerRotation;
    private double lastObservedProgress;
    private boolean previousDownloading;
    private boolean completing;
    private boolean initialized;
    private long shownAt;
    private long completeAt;
    private long observedNoticeRevision = -1L;
    /** Animated expanded bounds; compact entry/exit remains driven by {@link #expansion}. */
    private double animatedExpandedWidth = -1.0;
    private double animatedExpandedHeight = -1.0;
    /** Fades and gently offsets replacement content without interrupting the island surface. */
    private float contentTransition = 1f;
    /** Last notification snapshot used to distinguish an actual replacement from a hold refresh. */
    private IslandNoticeType observedNoticeType = IslandNoticeType.NONE;
    private String observedNoticeTitle = "";
    private String observedNoticeValue = "";
    /** Outgoing copy is kept briefly so updates flow into the latest notice rather than re-entering. */
    private String outgoingNoticeTitle = "";
    private String outgoingNoticeValue = "";
    private float noticeCopyTransition = 1f;
    /** Volume has a separate target animation; its track never jumps or restarts the island. */
    private double animatedNoticeProgress;

    private DownloadDynamicIsland() {
    }

    /** Render above the normal game HUD. */
    public static void render() {
        INSTANCE.renderInternal();
    }

    /** A live-size preview used by the HUD editor even when no download is active. */
    public static void renderEditorPreview() {
        INSTANCE.renderPreviewInternal();
    }

    /** Shows the active theme in the global island after the compact icon is used. */
    public static void showTheme(String themeName) {
        publishNotice(IslandNoticeType.THEME, "当前主题", safeNoticeValue(themeName, "默认主题"));
    }

    /** Shows the effective full-screen player scale in the global island. */
    public static void showPlayerScale(int scalePercent) {
        int safePercent = Math.max(50, Math.min(150, scalePercent));
        publishNotice(IslandNoticeType.SCALE, "播放器大小", safePercent + "%");
    }

    /** Keeps the island visible until the asynchronous playlist request finishes. */

    /** Shows the persisted music volume together with a matching progress indicator. */
    public static void showVolume(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        publishNotice(IslandNoticeType.VOLUME, "音乐音量", safePercent + "%");
    }
    /** Shows the stable transcode card; progress updates only move the bar and do not restart entry animation. */
    public static void beginTranscode(String sourceName, String targetName) {
        transcoding = true;
        transcodeProgress = 0.0;
        publishNotice(IslandNoticeType.TRANSCODING, "音频转码",
                formatTranscodeValue(sourceName, targetName), true);
    }

    /** Updates the transcode progress without publishing a new notice revision. */
    public static void updateTranscodeProgress(double progress) {
        transcodeProgress = DynamicIslandMath.clamp01(progress);
    }

    /** Shows the verified conversion result for the configured completion hold duration. */
    public static void finishTranscode(String sourceName, String targetName, long elapsedMillis) {
        transcoding = false;
        transcodeProgress = 1.0;
        publishNotice(IslandNoticeType.TRANSCODE_SUCCESS, "转码完成",
                formatTranscodeValue(sourceName, targetName) + " · " + Math.max(0L, elapsedMillis) + " ms");
    }

    /** Removes an interrupted transcode card before the normal playback-failure notice is shown. */
    public static void cancelTranscode() {
        transcoding = false;
        transcodeProgress = 0.0;
        if (noticeType == IslandNoticeType.TRANSCODING) {
            publishNotice(IslandNoticeType.NONE, "", "");
        }
    }

    private static String formatTranscodeValue(String sourceName, String targetName) {
        return safeNoticeValue(sourceName, "输入音频") + " → " + safeNoticeValue(targetName, "WAV");
    }
    public static void showPlaylistRefreshInProgress() {
        publishNotice(IslandNoticeType.REFRESHING, "歌单同步", "正在刷新…", true);
    }

    public static void showPlaylistRefreshSuccess(int playlistCount, long elapsedMillis) {
        int safeCount = Math.max(0, playlistCount);
        long safeElapsed = Math.max(0L, elapsedMillis);
        publishNotice(IslandNoticeType.REFRESH_SUCCESS, "歌单同步",
                "已同步 " + safeCount + " 个歌单 · " + safeElapsed + " ms");
    }

    public static void showPlaylistRefreshFailure(String message) {
        publishNotice(IslandNoticeType.REFRESH_ERROR, "歌单同步失败",
                safeNoticeValue(message, "网络请求失败"));
    }

    /** Keeps the island visible while a selected track is submitted to a playlist. */
    public static void showPlaylistTrackAddInProgress(String playlistName) {
        publishNotice(IslandNoticeType.PLAYLIST_TRACK_ADDING, "加入歌单",
                "正在加入「" + safeNoticeValue(playlistName, "目标歌单") + "」", true);
    }

    /** Announces a verified new playlist membership. */
    public static void showPlaylistTrackAddSuccess(String playlistName) {
        publishNotice(IslandNoticeType.PLAYLIST_TRACK_ADD_SUCCESS, "已加入歌单",
                "网易云已确认「" + safeNoticeValue(playlistName, "目标歌单") + "」");
    }

    /** Announces the separately verified no-op state without claiming a new add succeeded. */
    public static void showPlaylistTrackAlreadyExists(String playlistName) {
        publishNotice(IslandNoticeType.PLAYLIST_TRACK_ALREADY_EXISTS, "歌曲已在歌单中",
                "已确认「" + safeNoticeValue(playlistName, "目标歌单") + "」");
    }

    /** Announces that the request was not verified by the target playlist's trackIds. */
    public static void showPlaylistTrackAddFailure(String playlistName, String message) {
        String value = safeNoticeValue(message, "服务端未确认加入成功");
        publishNotice(IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR, "加入歌单失败",
                safeNoticeValue(playlistName, "目标歌单") + " · " + value);
    }

    /** Shows the stream tier and container resolved for the track that just started. */
    public static void showPlaybackQuality(String quality, String format) {
        String safeQuality = safeNoticeValue(quality, "标准");
        String safeFormat = safeNoticeValue(format, "未知").toUpperCase(java.util.Locale.ROOT);
        publishNotice(IslandNoticeType.QUALITY, "获取音质：" + safeQuality, "格式: " + safeFormat);
    }

    /**
     * Reports a generic MP4 payload detected from the downloaded bytes. The playback pipeline keeps
     * the header check but intentionally does not transcode this container.
     */
    public static void showUnsupportedMp4Container(String sourceName) {
        publishNotice(IslandNoticeType.PLAYBACK_ERROR, "检测到不兼容 MP4 容器",
                safeNoticeValue(sourceName, "当前音频") + " · 已跳过并尝试备用音源");
    }

    /** Reports a real playback failure without treating a user-initiated track switch as an error. */
    public static void showPlaybackFailure(String songName, String reason) {
        String safeSong = safeNoticeValue(songName, "当前歌曲");
        String safeReason = safeNoticeValue(reason, "无法获取可播放音频");
        publishNotice(IslandNoticeType.PLAYBACK_ERROR, "播放失败", safeSong + " · " + safeReason);
    }

    /** Shows the immediate recent-play upload result and request latency. */
    public static void showRecentPlayUploadSuccess(long elapsedMillis) {
        publishNotice(IslandNoticeType.RECENT_PLAY_SUCCESS, "听歌历史上报",
                "最近播放上报成功 " + Math.max(0L, elapsedMillis) + "ms");
    }

    public static void showRecentPlayUploadFailure(long elapsedMillis, String reason) {
        publishNotice(IslandNoticeType.RECENT_PLAY_ERROR, "听歌历史上报",
                "最近播放上报失败 " + Math.max(0L, elapsedMillis) + "ms · "
                        + safeNoticeValue(reason, "请求失败"));
    }

    /** Shows a cumulative listening-duration checkpoint result and request latency. */
    public static void showListeningDurationUploadSuccess(int playedSeconds, long elapsedMillis) {
        publishNotice(IslandNoticeType.LISTENING_DURATION_SUCCESS, "听歌时长上报",
                "听歌时长上报成功 时长" + Math.max(0, playedSeconds) + "秒 "
                        + Math.max(0L, elapsedMillis) + "ms");
    }

    public static void showListeningDurationUploadFailure(int playedSeconds, long elapsedMillis, String reason) {
        publishNotice(IslandNoticeType.LISTENING_DURATION_ERROR, "听歌时长上报",
                "听歌时长上报失败 时长" + Math.max(0, playedSeconds) + "秒 "
                        + Math.max(0L, elapsedMillis) + "ms · "
                        + safeNoticeValue(reason, "请求失败"));
    }

    /** Gives login and account validation a distinct, non-download success feedback. */
    public static void showNetworkConnectionSuccess(String target) {
        publishNotice(IslandNoticeType.NETWORK_SUCCESS, "网络连接成功",
                safeNoticeValue(target, "音乐服务") + " · 已建立连接");
    }

    /** Gives login and account validation a distinct, readable connection failure feedback. */
    public static void showNetworkConnectionFailure(String target, String reason) {
        publishNotice(IslandNoticeType.NETWORK_ERROR, "网络连接失败",
                safeNoticeValue(target, "音乐服务") + " · " + safeNoticeValue(reason, "请检查网络后重试"));
    }
    private static void publishNotice(IslandNoticeType type, String title, String value) {
        publishNotice(type, title, value, false);
    }

    private static void publishNotice(IslandNoticeType type, String title, String value, boolean persistent) {
        noticeType = type == null ? IslandNoticeType.NONE : type;
        noticeTitle = safeNoticeValue(title, "状态");
        noticeValue = safeNoticeValue(value, "—");
        noticePersistent = persistent;
        noticeShownAt = System.currentTimeMillis();
        noticeRevision++;
    }

    private static String safeNoticeValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Starts a fresh download snapshot. Publish the flag last for cross-thread visibility. */
    public static void beginDownload() {
        downloadProgress = 0.0;
        downloadSpeed = "0 b/s";
        downloadCompletedAt = 0L;
        downloadStartedAt = System.currentTimeMillis();
        downloading = true;
    }

    /** Updates progress and closes the active state after publishing the final 100% value. */
    public static void updateProgress(double progress) {
        downloadProgress = DynamicIslandMath.clamp01(progress);
        if (progress >= 1.0) {
            downloadCompletedAt = System.currentTimeMillis();
            downloading = false;
        }
    }

    public static void updateSpeed(String speed) {
        downloadSpeed = speed == null ? "" : speed;
    }

    /** Completes the current download even if the HTTP progress callback did not report 100%. */
    public static void finishDownload() {
        downloadProgress = 1.0;
        downloadCompletedAt = System.currentTimeMillis();
        downloading = false;
    }

    /** Hides an aborted/stale download without playing the success animation. */
    public static void cancelDownload() {
        downloadProgress = 0.0;
        downloadStartedAt = 0L;
        downloadCompletedAt = 0L;
        downloading = false;
    }

    private void renderInternal() {
        long now = System.currentTimeMillis();
        double rawProgress = DynamicIslandMath.clamp01(downloadProgress);
        boolean activeDownload = downloading;
        double sourceTranscodeProgress = DynamicIslandMath.clamp01(transcodeProgress);
        long sourceStartedAt = downloadStartedAt;
        long sourceCompletedAt = downloadCompletedAt;
        long sourceNoticeShownAt = noticeShownAt;
        long sourceNoticeRevision = noticeRevision;
        IslandNoticeType sourceNoticeType = noticeType;
        String sourceNoticeTitle = noticeTitle;
        String sourceNoticeValue = noticeValue;
        boolean sourceNoticePersistent = noticePersistent;

        if (!initialized) {
            initialized = true;
            previousDownloading = activeDownload;
            lastObservedProgress = rawProgress;
            animatedProgress = activeDownload ? rawProgress : 0.0;
            observedNoticeRevision = sourceNoticeRevision;
            observedNoticeType = sourceNoticeType;
            observedNoticeTitle = sourceNoticeTitle;
            observedNoticeValue = sourceNoticeValue;
            animatedNoticeProgress = sourceNoticeType == IslandNoticeType.VOLUME
                    ? DynamicIslandMath.parseNoticePercent(sourceNoticeValue) / 100.0
                    : sourceNoticeType == IslandNoticeType.TRANSCODING ? sourceTranscodeProgress : 0.0;
            if (activeDownload) {
                shownAt = sourceStartedAt > 0L ? sourceStartedAt : now;
            } else if (sourceCompletedAt > 0L && now - sourceCompletedAt < DynamicIslandMath.completionHoldMillis(HudConfig.dynamicIslandCompletionHoldSeconds)) {
                completing = true;
                completeAt = sourceCompletedAt;
                animatedProgress = 1.0;
            } else if (sourceNoticeType != IslandNoticeType.NONE
                    && (sourceNoticePersistent || now - sourceNoticeShownAt < DynamicIslandMath.completionHoldMillis(HudConfig.dynamicIslandCompletionHoldSeconds))) {
                shownAt = sourceNoticeShownAt;
            }
        }

        boolean noticeChanged = sourceNoticeRevision != observedNoticeRevision;
        if (noticeChanged) {
            boolean noticeContentChanged = sourceNoticeType != observedNoticeType
                    || !sourceNoticeTitle.equals(observedNoticeTitle)
                    || !sourceNoticeValue.equals(observedNoticeValue);
            // noticeShownAt is refreshed by each publisher to extend the hold window. shownAt
            // instead owns the surface-entry animation and stays stable while the island is visible.
            boolean islandAlreadyVisible = visibility > .035f || expansion > .05 || contentAlpha > .05f;
            observedNoticeRevision = sourceNoticeRevision;
            if (!activeDownload) {
                completing = false;
                successMorph = 0f;
                if (!islandAlreadyVisible) {
                    contentTransition = 0f;
                    noticeCopyTransition = 1f;
                    shownAt = sourceNoticeShownAt > 0L ? sourceNoticeShownAt : now;
                } else if (noticeContentChanged && noticeCopyTransition >= .84f) {
                    // The surface does not re-enter. Copy transitions are coalesced so a rapid
                    // key-repeat sequence resolves cleanly to its latest notification value.
                    outgoingNoticeTitle = observedNoticeTitle;
                    outgoingNoticeValue = observedNoticeValue;
                    noticeCopyTransition = 0f;
                }
            }
            observedNoticeType = sourceNoticeType;
            observedNoticeTitle = sourceNoticeTitle;
            observedNoticeValue = sourceNoticeValue;
        }

        boolean progressRestarted = activeDownload && rawProgress + .12 < lastObservedProgress;
        boolean justStarted = activeDownload && !previousDownloading;
        if (justStarted || progressRestarted) {
            contentTransition = 0f;
            completing = false;
            completeAt = 0L;
            successMorph = 0f;
            animatedProgress = rawProgress;
            shownAt = sourceStartedAt > 0L ? sourceStartedAt : now;
        }

        // Fast downloads may begin and finish between two rendered frames. The final progress
        // edge keeps a short success state visible instead of silently disappearing.
        boolean completedBetweenFrames = !activeDownload
                && rawProgress >= .995
                && lastObservedProgress < .995;
        boolean newlyObservedCompletion = !activeDownload
                && sourceCompletedAt > completeAt
                && now - sourceCompletedAt < DynamicIslandMath.completionHoldMillis(HudConfig.dynamicIslandCompletionHoldSeconds);
        if (!activeDownload && (previousDownloading || completedBetweenFrames || newlyObservedCompletion)) {
            if (rawProgress >= .965) {
                completing = true;
                completeAt = newlyObservedCompletion ? sourceCompletedAt : now;
                animatedProgress = Math.max(animatedProgress, rawProgress);
            } else {
                completing = false;
            }
        }

        if (!activeDownload && sourceCompletedAt == 0L && rawProgress < .965) {
            completing = false;
        }

        if (activeDownload) {
            completing = false;
            animatedProgress = Interpolations.interpolate(animatedProgress, rawProgress, .20f);
        } else if (completing) {
            animatedProgress = Interpolations.interpolate(animatedProgress, 1.0, .30f);
        }

        double noticeProgressTarget = sourceNoticeType == IslandNoticeType.VOLUME
                ? DynamicIslandMath.parseNoticePercent(sourceNoticeValue) / 100.0
                : sourceNoticeType == IslandNoticeType.TRANSCODING ? sourceTranscodeProgress : 0.0;
        if (sourceNoticeType == IslandNoticeType.VOLUME
                || sourceNoticeType == IslandNoticeType.TRANSCODING) {
            // Repeated transcode/volume updates converge to one continuous bar animation.
            animatedNoticeProgress = Interpolations.interpolate(animatedNoticeProgress, noticeProgressTarget, .26f);
        } else {
            animatedNoticeProgress = Interpolations.interpolate(animatedNoticeProgress, 0.0, .24f);
        }

        boolean holdingCompletion = completing && now - completeAt < DynamicIslandMath.completionHoldMillis(HudConfig.dynamicIslandCompletionHoldSeconds);
        boolean activeNotice = sourceNoticeType != IslandNoticeType.NONE
                && sourceNoticeShownAt > 0L
                && now >= sourceNoticeShownAt
                && (sourceNoticePersistent || now - sourceNoticeShownAt < DynamicIslandMath.completionHoldMillis(HudConfig.dynamicIslandCompletionHoldSeconds));
        boolean noticeMode = !activeDownload && !holdingCompletion && activeNotice;
        boolean enabled = HudConfig.dynamicIslandEnabled;
        boolean shouldShow = enabled && (activeDownload || holdingCompletion || activeNotice);

        visibility = Interpolations.interpolate(visibility, shouldShow ? 1f : 0f,
                shouldShow ? .28f : .22f);
        expansion = Interpolations.interpolate(expansion, shouldShow ? 1.0 : 0.0,
                shouldShow ? .24f : .25f);
        float contentTarget = shouldShow && expansion > .30 ? 1f : 0f;
        contentAlpha = Interpolations.interpolate(contentAlpha, contentTarget,
                shouldShow ? .32f : .24f);
        contentTransition = Interpolations.interpolate(contentTransition, shouldShow ? 1f : 0f,
                shouldShow ? .38f : .26f);
        noticeCopyTransition = Interpolations.interpolate(noticeCopyTransition, 1f,
                shouldShow ? .32f : .26f);
        successMorph = Interpolations.interpolate(successMorph,
                enabled && holdingCompletion ? 1f : 0f, .28f);

        double frameDelta = Math.min(4.0, Math.max(0.0, RenderSystem.getFrameDeltaTime()));
        spinnerRotation = (spinnerRotation + frameDelta * 7.2) % 360.0;

        previousDownloading = activeDownload;
        lastObservedProgress = rawProgress;

        if (visibility <= .012f && !shouldShow) {
            completing = false;
            successMorph = 0f;
            return;
        }

        final double easedExpansion = DynamicIslandMath.smoothStep(expansion);
        final float alpha = DynamicIslandMath.clamp01f(visibility);
        final float renderedContentTransition = DynamicIslandMath.clamp01f(contentTransition);
        final String speed = downloadSpeed;
        final boolean renderNotice = noticeMode;
        final IslandNoticeType renderedNoticeType = sourceNoticeType;
        final String renderedNoticeTitle = sourceNoticeTitle;
        final String renderedNoticeValue = sourceNoticeValue;
        final String renderedOutgoingNoticeTitle = outgoingNoticeTitle;
        final String renderedOutgoingNoticeValue = outgoingNoticeValue;
        final float renderedNoticeCopyTransition = DynamicIslandMath.clamp01f(noticeCopyTransition);
        final double renderedNoticeProgress = DynamicIslandMath.clamp01(animatedNoticeProgress);
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                drawIsland(easedExpansion, alpha, renderedContentTransition, animatedProgress, successMorph, speed,
                        System.currentTimeMillis(), shownAt, false, renderNotice,
                        renderedNoticeType, renderedNoticeTitle, renderedNoticeValue,
                        renderedOutgoingNoticeTitle, renderedOutgoingNoticeValue,
                        renderedNoticeCopyTransition, renderedNoticeProgress, sourceNoticePersistent);
            }
        });
    }

    private void renderPreviewInternal() {
        if (RenderSystem.getWidth() <= 0 || RenderSystem.getHeight() <= 0) return;
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                float alpha = HudConfig.dynamicIslandEnabled ? .96f : .46f;
                drawIsland(1.0, alpha, 1f, .64, 0f,
                        HudConfig.dynamicIslandEnabled ? "1.8 MB/s" : "灵动岛已关闭",
                        System.currentTimeMillis(), 0L, true, false,
                        IslandNoticeType.NONE, "", "", "", "", 1f, 0.0, false);
            }
        });
    }

    private void renderIsolated(Runnable renderer) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        api.getGLStateManager().pushMatrix();
        try {
            // The island is a global overlay. It must not inherit a scissor/stencil region left by
            // the player UI or another mod, otherwise the left-side status icon can be cut in half.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            renderer.run();
        } finally {
            api.getGLStateManager().popMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawIsland(double expansionValue, float alpha, float contentFade,
                            double progress, float success, String speedValue, long now,
                            long animationStart, boolean preview, boolean noticeMode,
                            IslandNoticeType activeNoticeType, String activeNoticeTitle,
                            String activeNoticeValue, String outgoingNoticeTitle,
                            String outgoingNoticeValue, float noticeCopyBlend,
                            double noticeProgress, boolean activeNoticePersistent) {
        double configuredScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandScale, .60, 1.35);
        double screenWidth = RenderSystem.getWidth();
        DynamicIslandStyle style = getStyle();
        IslandLayout layout = createIslandLayout(style, noticeMode, activeNoticeType, activeNoticeTitle,
                activeNoticeValue, speedValue, preview, configuredScale, screenWidth);

        double compactWidth = style == DynamicIslandStyle.COMPACT ? 46.0
                : (style == DynamicIslandStyle.MUSIC_FOCUS ? 48.0 : COMPACT_WIDTH);
        double compactHeight = style == DynamicIslandStyle.COMPACT ? 14.0 : COMPACT_HEIGHT;
        double expandedWidth = preview ? layout.targetWidth
                : animateExpandedDimension(animatedExpandedWidth, layout.targetWidth, true);
        double expandedHeight = preview ? layout.targetHeight
                : animateExpandedDimension(animatedExpandedHeight, layout.targetHeight, false);
        if (!preview) {
            animatedExpandedWidth = expandedWidth;
            animatedExpandedHeight = expandedHeight;
        }

        double width = DynamicIslandMath.lerp(compactWidth, expandedWidth, expansionValue);
        double height = DynamicIslandMath.lerp(compactHeight, expandedHeight, expansionValue);
        double centerX = screenWidth * .5;
        double visibilityEase = DynamicIslandMath.smoothStep(alpha);
        double y = TOP_MARGIN - (height + 11.0) * (1.0 - visibilityEase);

        double entryScale = 1.0;
        if (!preview && animationStart > 0L) {
            double entryProgress = DynamicIslandMath.clamp01((now - animationStart) / 420.0);
            double cubicOut = 1.0 - Math.pow(1.0 - entryProgress, 3.0);
            double restrainedOvershoot = Math.sin(entryProgress * Math.PI)
                    * (1.0 - entryProgress) * .025;
            entryScale = .965 + .035 * cubicOut + restrainedOvershoot;
        }

        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, TOP_MARGIN, configuredScale);
        scaleAtPos(centerX, y + height * .5, entryScale);

        double x = centerX - width * .5;
        boolean systemCard = style == DynamicIslandStyle.SYSTEM_CARD;
        boolean musicFocus = style == DynamicIslandStyle.MUSIC_FOCUS;
        double radius = systemCard ? Math.min(11.0, height * .25)
                : (musicFocus ? Math.min(14.0, height * .31)
                : (style == DynamicIslandStyle.CARD ? Math.min(12.0, height * .30) : height * .5));
        int accentColor = NCMTheme.getAccentColor();
        int iconAccentColor = DynamicIslandMath.brightenAccent(accentColor, .30f);

        // Keep the shadow on the island's own rounded silhouette. The prior expanded,
        // left-shifted rectangle looked like a separate dark background canvas.
        float shadowAlpha = alpha * (style == DynamicIslandStyle.GLASS ? .13f : (musicFocus ? .23f : .19f));
        roundedRect(x + 1.0, y + 1.8, width, height, radius,
                hexColor(0f, 0f, 0f, shadowAlpha));
        roundedRect(x + 2.0, y + 3.0, width, height, radius,
                hexColor(0f, 0f, 0f, shadowAlpha * .42f));
        if (style == DynamicIslandStyle.GLASS) {
            roundedRect(x, y, width, height, radius, RenderSystem.reAlpha(accentColor, alpha * .20f));
            roundedRect(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height * .46),
                    Math.max(1.0, radius - 1.0), hexColor(1f, 1f, 1f, alpha * .075f));
            roundedOutline(x, y, width, height, radius, .75,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 62f)));
        } else if (style == DynamicIslandStyle.CARD) {
            roundedRect(x, y, width, height, radius, hexColor(.028f, .034f, .046f, alpha * .985f));
            roundedRect(x, y + radius, 2.2, Math.max(1.0, height - radius * 2.0), 1.1,
                    RenderSystem.reAlpha(accentColor, alpha * .90f));
            roundedOutline(x, y, width, height, radius, .65,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 30f)));
        } else if (musicFocus) {
            // Music-focused card: the surface expands first, then the animated artwork and copy settle in.
            roundedRect(x, y, width, height, radius, hexColor(.010f, .014f, .024f, alpha * .99f));
            roundedRect(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height - 2.0),
                    Math.max(1.0, radius - 1.0), hexColor(.050f, .064f, .098f, alpha * .46f));
            roundedRect(x + 1.2, y + 1.2, Math.max(1.0, width - 2.4), Math.max(1.0, height * .36),
                    Math.max(1.0, radius - 1.2), RenderSystem.reAlpha(accentColor, alpha * .10f));
            roundedOutline(x, y, width, height, radius, .80,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 54f)));
            roundedRect(x + 8.0, y + height - 5.0, Math.max(1.0, width - 16.0), 1.0, .5,
                    RenderSystem.reAlpha(accentColor, alpha * .22f));
        } else if (systemCard) {
            // Dedicated content-driven system notification card inspired by the supplied reference project.
            roundedRect(x, y, width, height, radius, hexColor(.025f, .031f, .043f, alpha * .99f));
            roundedRect(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height - 2.0),
                    Math.max(1.0, radius - 1.0), hexColor(.075f, .088f, .118f, alpha * .32f));
            roundedOutline(x, y, width, height, radius, .80,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 46f)));
            double tileSize = Math.min(24.0, Math.max(16.0, height - 16.0));
            roundedRect(x + 8.0, y + (height - tileSize) * .5, tileSize, tileSize,
                    Math.min(7.0, tileSize * .30), RenderSystem.reAlpha(accentColor, alpha * .26f));
            roundedOutline(x + 8.0, y + (height - tileSize) * .5, tileSize, tileSize,
                    Math.min(7.0, tileSize * .30), .65,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 36f)));
        } else {
            roundedRect(x, y, width, height, radius, hexColor(.012f, .014f, .020f, alpha * .985f));
            roundedOutline(x, y, width, height, radius, .65,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 20f)));
        }
        if (!systemCard && !musicFocus) {
            roundedRect(x + radius * .70, y + .8, Math.max(2.0, width - radius * 1.40), .75, .38,
                    hexColor(1f, 1f, 1f, alpha * (style == DynamicIslandStyle.GLASS ? .13f : .055f)));
        }

        // Keep the icon safely inside the rounded silhouette and reserve a real gap before text.
        // Previously the 1.16x icon scale could push its outer edge into a stale clip or the pill's
        // curved shoulder, making the left edge look incomplete.
        // SYSTEM_CARD reserves a 24px tile at x + 8, whose exact center is x + 20.
        // Keep the glyph center tied to that tile rather than its wider text gutter.
        double iconInset = systemCard ? 20.0 : (musicFocus ? 26.0 : (style == DynamicIslandStyle.CARD ? 25.0 : 23.0));
        double iconExpandedX = centerX - expandedWidth * .5 + iconInset;
        double iconX = DynamicIslandMath.lerp(centerX, iconExpandedX, expansionValue);
        double iconY = y + height * .5;
        double pulse = .5 + .5 * Math.sin(now / 260.0);
        float iconAlpha = alpha * (.87f + (float) pulse * .10f);

        api.getGLStateManager().pushMatrix();
        if (musicFocus) {
            renderMusicFocusArtwork(iconX, iconY, height, iconAlpha, accentColor, iconAccentColor, now,
                    preview, noticeMode, activeNoticeType, success);
        } else {
            double iconPlate = systemCard ? 17.0 : 18.4;
            scaleAtPos(iconX, iconY, systemCard ? 1.0 : 1.08);
            roundedRect(iconX - iconPlate * .5, iconY - iconPlate * .5, iconPlate, iconPlate,
                    iconPlate * .5, RenderSystem.reAlpha(accentColor, iconAlpha * .22f));
            roundedOutline(iconX - iconPlate * .5, iconY - iconPlate * .5, iconPlate, iconPlate,
                    iconPlate * .5, .85, new Color(255, 255, 255, DynamicIslandMath.clamp255(iconAlpha * 76f)));
            if (noticeMode) {
                renderNoticeIcon(activeNoticeType, iconX, iconY, iconAlpha, iconAccentColor, now);
            } else {
                renderSpinner(iconX, iconY, iconAlpha * (1f - success), iconAccentColor, now, preview);
                renderSuccess(iconX, iconY, alpha * success);
            }
        }
        api.getGLStateManager().popMatrix();

        float textAlpha = alpha * contentAlpha * (float) expansionValue * DynamicIslandMath.clamp01f(contentFade);
        if (preview) textAlpha = alpha * (float) expansionValue;
        boolean staticVolumeCopy = noticeMode && activeNoticeType == IslandNoticeType.VOLUME;
        boolean transcodeCopy = noticeMode && activeNoticeType == IslandNoticeType.TRANSCODING;
        // Volume is frequently updated from key-repeat. Its labels deliberately skip both the
        // island-content entry and copy-transition animations, while the bar still interpolates.
        float renderedTextAlpha = staticVolumeCopy
                ? (expansionValue >= .74 ? alpha : 0f) : textAlpha;
        if (renderedTextAlpha > .01f) {
            double textLeft = x + (systemCard ? 44.0 : (musicFocus ? 55.0 : (style == DynamicIslandStyle.CARD ? 39.0 : 37.0)));
            double textRight = x + width - (systemCard ? 12.0 : (musicFocus ? 12.0 : 9.0));
            double textCenter = (textLeft + textRight) * .5;
            double copySlide = (1.0 - DynamicIslandMath.clamp01(contentFade)) * CONTENT_CHANGE_SLIDE;
            double baseTitleY = y + (systemCard ? 9.0 : (musicFocus ? 10.0 : 5.4));
            double baseValueY = y + (systemCard ? 23.0 : (musicFocus ? 25.0 : 16.6));
            double titleY = baseTitleY + copySlide;
            double valueY = baseValueY + copySlide;

            if (noticeMode) {
                double availableWidth = Math.max(28.0, textRight - textLeft);
                if (staticVolumeCopy) {
                    double staticTitleY = baseTitleY;
                    double staticValueY = baseValueY;
                    drawCenteredIslandText(FontManager.pf12, layout.title, textCenter, staticTitleY,
                            availableWidth, layout.textScale,
                            hexColor(.62f, .67f, .76f, renderedTextAlpha * .96f));
                    drawCenteredIslandText(FontManager.pf14bold, layout.value, textCenter, staticValueY,
                            availableWidth, layout.textScale, hexColor(1f, 1f, 1f, renderedTextAlpha));
                } else if (transcodeCopy) {
                    double transcodeTitleY = baseTitleY;
                    double transcodeValueY = baseValueY;
                    drawCenteredIslandText(FontManager.pf12, layout.title, textCenter, transcodeTitleY,
                            availableWidth, layout.textScale,
                            hexColor(.62f, .67f, .76f, renderedTextAlpha * .96f));
                    drawCenteredIslandText(FontManager.pf12, layout.value, textCenter, transcodeValueY,
                            availableWidth, layout.textScale,
                            hexColor(1f, 1f, 1f, renderedTextAlpha));
                    String percent = Math.max(0, Math.min(100,
                            (int) Math.round(DynamicIslandMath.clamp01(noticeProgress) * 100.0))) + "%";
                    drawCenteredIslandText(FontManager.pf12bold, percent, textRight - 10.0, transcodeValueY,
                            20.0, layout.textScale,
                            hexColor(.58f, .90f, 1f, renderedTextAlpha));                } else {
                    double copyBlend = DynamicIslandMath.clamp01(noticeCopyBlend);
                    double noticeSlide = CONTENT_CHANGE_SLIDE * .62;

                    // A notification refresh keeps the island itself stable. The previous copy exits
                    // upward while the latest value enters from below; rapid updates are coalesced by
                    // renderInternal rather than constantly restarting this transition.
                    if (copyBlend < .995) {
                        float outgoingAlpha = textAlpha * (float) (1.0 - copyBlend);
                        if (outgoingAlpha > .01f) {
                            drawCenteredIslandText(FontManager.pf12,
                                    safeNoticeValue(outgoingNoticeTitle, layout.title), textCenter,
                                    titleY - noticeSlide * copyBlend, availableWidth, layout.textScale,
                                    hexColor(.62f, .67f, .76f, outgoingAlpha * .96f));
                            drawCenteredIslandText(FontManager.pf14bold,
                                    safeNoticeValue(outgoingNoticeValue, layout.value), textCenter,
                                    valueY - noticeSlide * copyBlend, availableWidth, layout.textScale,
                                    hexColor(1f, 1f, 1f, outgoingAlpha));
                        }
                    }

                    float incomingAlpha = textAlpha * (float) copyBlend;
                    if (incomingAlpha > .01f) {
                        drawCenteredIslandText(FontManager.pf12, layout.title, textCenter,
                                titleY + noticeSlide * (1.0 - copyBlend), availableWidth, layout.textScale,
                                hexColor(.62f, .67f, .76f, incomingAlpha * .96f));
                        drawCenteredIslandText(FontManager.pf14bold, layout.value, textCenter,
                                valueY + noticeSlide * (1.0 - copyBlend), availableWidth, layout.textScale,
                                hexColor(1f, 1f, 1f, incomingAlpha));
                    }
                }

                if (staticVolumeCopy || transcodeCopy) {
                    double progressX = textLeft;
                    double progressWidth = Math.max(28.0, textRight - textLeft);
                    double progressHeight = systemCard ? 3.2 : (musicFocus ? 2.6 : 2.8);
                    double progressY = y + height - (systemCard ? 8.2 : (musicFocus ? 7.0 : 6.0));
                    double progressRadius = progressHeight * .5;
                    roundedRect(progressX, progressY, progressWidth, progressHeight, progressRadius,
                            hexColor(1f, 1f, 1f, renderedTextAlpha * .12f));
                    double fillWidth = progressWidth * DynamicIslandMath.clamp01(noticeProgress);
                    if (fillWidth > .10) {
                        roundedRectGradientHorizontal(progressX, progressY, fillWidth, progressHeight,
                                Math.min(progressRadius, fillWidth * .5),
                                DynamicIslandMath.colorWithAlpha(accentColor, renderedTextAlpha * .98f),
                                new Color(103, 216, 255, DynamicIslandMath.clamp255(renderedTextAlpha * 255f)));
                    }
                }
            } else {
                float loadingAlpha = textAlpha * (1f - success);
                float completeAlpha = textAlpha * success;
                String status = success > .52f ? "完成" : Math.max(0, Math.min(100,
                        (int) Math.round(DynamicIslandMath.clamp01(progress) * 100.0))) + "%";
                double statusWidth = Math.max(25.0,
                        FontManager.pf12bold.getStringWidthD(status) * layout.textScale + 10.0);
                double statusX = textRight - statusWidth;
                double bodyRight = statusX - 5.0;
                double bodyCenter = (textLeft + bodyRight) * .5;
                double bodyWidth = Math.max(28.0, bodyRight - textLeft);

                drawCenteredIslandText(FontManager.pf14bold, layout.title, bodyCenter, titleY,
                        bodyWidth, layout.textScale, hexColor(1f, 1f, 1f, loadingAlpha));
                drawCenteredIslandText(FontManager.pf14bold, "加载完成", bodyCenter, titleY,
                        bodyWidth, layout.textScale, hexColor(1f, 1f, 1f, completeAlpha));
                drawCenteredIslandText(FontManager.pf12, layout.value, bodyCenter, valueY,
                        bodyWidth, layout.textScale, hexColor(.65f, .69f, .78f, loadingAlpha * .94f));
                drawCenteredIslandText(FontManager.pf12, "可以开始播放", bodyCenter, valueY,
                        bodyWidth, layout.textScale, hexColor(.59f, .90f, .67f, completeAlpha));

                int statusColor = success > .52f
                        ? hexColor(.58f, 1f, .68f, textAlpha)
                        : hexColor(.82f, .85f, .91f, textAlpha);
                roundedRect(statusX, y + (systemCard ? 8.5 : (musicFocus ? 11.0 : 4.2)), statusWidth, 12.5, 6.25,
                        success > .52f ? hexColor(.18f, .55f, .32f, textAlpha * .34f)
                                : hexColor(1f, 1f, 1f, textAlpha * .10f));
                drawCenteredIslandText(FontManager.pf12bold, status, statusX + statusWidth * .5,
                        y + (systemCard ? 10.4 : (musicFocus ? 12.9 : 6.1)), statusWidth - 4.0, layout.textScale, statusColor);

                double progressX = x + (systemCard ? 8.0 : (musicFocus ? 10.0 : 9.0));
                double progressY = y + height - (systemCard ? 7.0 : (musicFocus ? 6.8 : 3.7));
                double progressWidth = width - (systemCard ? 16.0 : (musicFocus ? 20.0 : 18.0));
                double progressHeight = Math.min(systemCard ? 3.8 : (musicFocus ? 3.0 : height * .22),
                        DynamicIslandMath.clamp(HudConfig.dynamicIslandProgressHeight, .75, 4.0));
                double progressRadius = progressHeight * .5;
                roundedRect(progressX, progressY, progressWidth, progressHeight, progressRadius,
                        hexColor(1f, 1f, 1f, textAlpha * .10f));
                double fillWidth = progressWidth * DynamicIslandMath.clamp01(progress);
                if (fillWidth > .10) {
                    Color startColor = DynamicIslandMath.colorWithAlpha(accentColor, textAlpha * .94f);
                    Color endColor = new Color(103, 216, 255, DynamicIslandMath.clamp255(textAlpha * 255f));
                    roundedRectGradientHorizontal(progressX, progressY, fillWidth, progressHeight,
                            Math.min(progressRadius, fillWidth * .5), startColor, endColor);
                }
            }
        }
        api.getGLStateManager().popMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private IslandLayout createIslandLayout(DynamicIslandStyle style, boolean noticeMode,
                                            IslandNoticeType noticeType, String noticeTitleValue,
                                            String noticeBodyValue, String speedValue, boolean preview,
                                            double configuredScale, double screenWidth) {
        DynamicIslandLayoutCalculator.LayoutData layout = DynamicIslandLayoutCalculator.calculate(
                style, noticeMode, noticeMode && noticeType == IslandNoticeType.VOLUME,
                noticeTitleValue, noticeBodyValue, speedValue, preview, configuredScale, screenWidth,
                COMPACT_WIDTH, MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        return new IslandLayout(layout.title, layout.value, layout.textScale,
                layout.targetWidth, layout.targetHeight);
    }
    private double animateExpandedDimension(double current, double target, boolean width) {
        if (current <= .0) return target;
        // Expansion reacts a little faster than contraction; this reads as deliberate rather than
        // as a layout jump when a notification changes from short to long or back again.
        float speed = target > current ? (width ? .42f : .38f) : (width ? .24f : .22f);
        return Interpolations.interpolate(current, target, speed);
    }

    private void drawCenteredIslandText(CFontRenderer font, String text, double centerX, double y,
                                         double maxWidth, double textScale, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        double safeScale = DynamicIslandMath.clamp(textScale, MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        String trimmed = trimIslandText(font, text, Math.max(18.0, maxWidth / safeScale));
        if (trimmed.isEmpty()) return;
        double textWidth = font.getStringWidthD(trimmed) * safeScale;
        font.drawString(trimmed, centerX - textWidth * .5, y, safeScale, color);
    }

    private String trimIslandText(CFontRenderer font, String text, double maxRawWidth) {
        if (font.getStringWidthD(text) <= maxRawWidth) return text;
        final String ellipsis = "...";
        if (font.getStringWidthD(ellipsis) > maxRawWidth) return "";
        int end = text.length();
        while (end > 0) {
            String candidate = text.substring(0, end) + ellipsis;
            if (font.getStringWidthD(candidate) <= maxRawWidth) return candidate;
            end--;
        }
        return ellipsis;
    }

    private static final class IslandLayout {
        final String title;
        final String value;
        final double textScale;
        final double targetWidth;
        final double targetHeight;

        IslandLayout(String title, String value, double textScale, double targetWidth, double targetHeight) {
            this.title = title;
            this.value = value;
            this.textScale = textScale;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
        }
    }

    private void renderNoticeIcon(IslandNoticeType type, double centerX, double centerY,
                                  float alpha, int accentColor, long now) {
        if (alpha <= .01f) return;
        int foreground = hexColor(.94f, .95f, .98f, alpha);
        if (type == IslandNoticeType.REFRESHING || type == IslandNoticeType.PLAYLIST_TRACK_ADDING
                || type == IslandNoticeType.TRANSCODING) {
            renderSpinner(centerX, centerY, alpha, accentColor, now, false);
            return;
        }
        if (type == IslandNoticeType.REFRESH_SUCCESS
                || type == IslandNoticeType.PLAYLIST_TRACK_ADD_SUCCESS
                || type == IslandNoticeType.PLAYLIST_TRACK_ALREADY_EXISTS
                || type == IslandNoticeType.TRANSCODE_SUCCESS) {
            drawRotatedPill(centerX - 1.8, centerY + .8, 3.8, 1.25, 43f,
                    hexColor(.70f, 1f, .77f, alpha));
            drawRotatedPill(centerX + 1.6, centerY - .4, 6.2, 1.25, -47f,
                    hexColor(.70f, 1f, .77f, alpha));
            return;
        }
        if (type == IslandNoticeType.NETWORK_SUCCESS) {
            drawNoticeFontelloIcon(FontelloIcons.LINK, centerX, centerY, alpha,
                    hexColor(.70f, 1f, .77f, alpha));
            return;
        }
        if (type == IslandNoticeType.NETWORK_ERROR) {
            drawNoticeFontelloIcon(FontelloIcons.UNLINK, centerX, centerY, alpha,
                    hexColor(1f, .48f, .50f, alpha));
            return;
        }
        if (type == IslandNoticeType.REFRESH_ERROR || type == IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR
                || type == IslandNoticeType.PLAYBACK_ERROR) {
            drawRotatedPill(centerX, centerY, 10.0, 1.25, 45f,
                    hexColor(1f, .48f, .50f, alpha));
            drawRotatedPill(centerX, centerY, 10.0, 1.25, -45f,
                    hexColor(1f, .48f, .50f, alpha));
            return;
        }
        if (type == IslandNoticeType.THEME || type == IslandNoticeType.STYLE) {
            roundedRect(centerX - 2.5, centerY - 2.5, 5.0, 5.0, 2.5,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX - .5, centerY - 6.0, 1.0, 1.8, .5, foreground);
            roundedRect(centerX - .5, centerY + 4.2, 1.0, 1.8, .5, foreground);
            roundedRect(centerX - 6.0, centerY - .5, 1.8, 1.0, .5, foreground);
            roundedRect(centerX + 4.2, centerY - .5, 1.8, 1.0, .5, foreground);
            return;
        }
        if (type == IslandNoticeType.VOLUME) {
            // Speaker body, cone and two sound pulses remain crisp at every island scale.
            roundedRect(centerX - 5.6, centerY - 2.0, 2.3, 4.0, .7, foreground);
            roundedRect(centerX - 3.7, centerY - 3.8, 3.6, 7.6, .8,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX + 2.0, centerY - 3.5, 1.1, 2.4, .55,
                    RenderSystem.reAlpha(accentColor, alpha * .82f));
            roundedRect(centerX + 3.8, centerY - 5.1, 1.1, 3.9, .55,
                    RenderSystem.reAlpha(accentColor, alpha * .52f));
            roundedRect(centerX + 2.0, centerY + 1.1, 1.1, 2.4, .55,
                    RenderSystem.reAlpha(accentColor, alpha * .82f));
            roundedRect(centerX + 3.8, centerY + 1.2, 1.1, 3.9, .55,
                    RenderSystem.reAlpha(accentColor, alpha * .52f));
            return;
        }
        if (type == IslandNoticeType.QUALITY) {
            // A compact equalizer makes a playback-quality notice distinct from other status states.
            roundedRect(centerX - 5.1, centerY + 1.5, 2.2, 3.5, 1.1,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX - 1.1, centerY - 1.3, 2.2, 6.3, 1.1, foreground);
            roundedRect(centerX + 2.9, centerY - 4.5, 2.2, 9.5, 1.1,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }

        double left = centerX - 4.8;
        double top = centerY - 4.8;
        double right = centerX + 4.8;
        double bottom = centerY + 4.8;
        double thickness = 1.0;
        double arm = 3.2;
        roundedRect(left, top, arm, thickness, .5, foreground);
        roundedRect(left, top, thickness, arm, .5, foreground);
        roundedRect(right - arm, top, arm, thickness, .5, foreground);
        roundedRect(right - thickness, top, thickness, arm, .5, foreground);
        roundedRect(left, bottom - thickness, arm, thickness, .5, foreground);
        roundedRect(left, bottom - arm, thickness, arm, .5, foreground);
        roundedRect(right - arm, bottom - thickness, arm, thickness, .5, foreground);
        roundedRect(right - thickness, bottom - arm, thickness, arm, .5, foreground);
    }

    private void drawNoticeFontelloIcon(String glyph, double centerX, double centerY, float alpha, int color) {
        if (glyph == null || glyph.isEmpty() || alpha <= .01f || FontManager.fontello16 == null) return;
        double width = FontManager.fontello16.getStringWidthD(glyph);
        double height = Math.max(1.0, FontManager.fontello16.getFontHeight());
        // Do not use a fixed baseline: Fontello glyphs have different ascender and
        // descender bounds. Metric centering keeps every left-side notice icon in
        // the middle of its circular plate.
        FontManager.fontello16.drawString(glyph, centerX - width * .5, centerY - height * .5, color);
    }

    private void renderSpinner(double centerX, double centerY, float alpha, int accentColor,
                               long now, boolean preview) {
        if (alpha <= .01f) return;
        double rotation = preview ? (now / 7.0) % 360.0 : spinnerRotation;
        final int segments = 8;
        for (int segment = 0; segment < segments; segment++) {
            float trail = 1f - segment / (float) segments;
            float segmentAlpha = alpha * (.14f + .86f * trail * trail);
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate((float) (rotation + segment * (360f / segments)), 0, 0, 1);
            roundedRect(-.82, -6.4, 1.64, 3.0, .82,
                    RenderSystem.reAlpha(accentColor, segmentAlpha));
            api.getGLStateManager().popMatrix();
        }
    }

    /**
     * Original music-card artwork for the MUSIC_FOCUS style. It deliberately uses primitive OpenGL
     * shapes instead of Android assets: the rotating accent bands make the compact and expanded
     * states feel connected while remaining safe for Minecraft 1.8.9's fixed-function pipeline.
     */
    private void renderMusicFocusArtwork(double centerX, double centerY, double islandHeight, float alpha,
                                         int accentColor, int iconAccentColor, long now, boolean preview,
                                         boolean noticeMode, IslandNoticeType noticeType, float success) {
        double tileSize = Math.min(31.0, Math.max(21.0, islandHeight - 16.0));
        double tileRadius = Math.min(8.0, tileSize * .28);
        double beat = .985 + .025 * (.5 + .5 * Math.sin((preview ? now / 8.0 : now / 290.0)));
        scaleAtPos(centerX, centerY, beat);
        roundedRect(centerX - tileSize * .5, centerY - tileSize * .5, tileSize, tileSize, tileRadius,
                RenderSystem.reAlpha(accentColor, alpha * .66f));
        roundedRect(centerX - tileSize * .5 + 1.0, centerY - tileSize * .5 + 1.0,
                Math.max(1.0, tileSize - 2.0), Math.max(1.0, tileSize - 2.0), Math.max(1.0, tileRadius - 1.0),
                hexColor(.018f, .026f, .045f, alpha * .76f));
        roundedOutline(centerX - tileSize * .5, centerY - tileSize * .5, tileSize, tileSize, tileRadius, .80,
                new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 92f)));

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().rotate((float) ((preview ? now / 7.0 : spinnerRotation * .75) % 360.0), 0, 0, 1);
        roundedRect(-tileSize * .34, -1.25, tileSize * .68, 2.5, 1.25,
                RenderSystem.reAlpha(iconAccentColor, alpha * .58f));
        roundedRect(-1.25, -tileSize * .34, 2.5, tileSize * .68, 1.25,
                RenderSystem.reAlpha(accentColor, alpha * .46f));
        api.getGLStateManager().popMatrix();

        double coreSize = Math.max(8.0, tileSize * .37);
        roundedRect(centerX - coreSize * .5, centerY - coreSize * .5, coreSize, coreSize, coreSize * .5,
                hexColor(.012f, .016f, .029f, alpha * .96f));
        if (noticeMode) {
            api.getGLStateManager().pushMatrix();
            scaleAtPos(centerX, centerY, .64);
            renderNoticeIcon(noticeType, centerX, centerY, alpha, iconAccentColor, now);
            api.getGLStateManager().popMatrix();
        } else {
            renderSpinner(centerX, centerY, alpha * (1f - success) * .72f, iconAccentColor, now, preview);
            renderSuccess(centerX, centerY, alpha * success);
        }
    }
    private void renderSuccess(double centerX, double centerY, float alpha) {
        if (alpha <= .01f) return;
        double scale = .78 + .22 * DynamicIslandMath.smoothStep(successMorph);
        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, centerY, scale);
        roundedOutline(centerX - 6.2, centerY - 6.2, 12.4, 12.4, 6.2, .75,
                new Color(91, 240, 129, DynamicIslandMath.clamp255(alpha * 220f)));
        drawRotatedPill(centerX - 1.8, centerY + .8, 3.8, 1.25, 43f,
                hexColor(.70f, 1f, .77f, alpha));
        drawRotatedPill(centerX + 1.6, centerY - .4, 6.2, 1.25, -47f,
                hexColor(.70f, 1f, .77f, alpha));
        api.getGLStateManager().popMatrix();
    }

    private void drawRotatedPill(double centerX, double centerY, double width, double height,
                                 float rotation, int color) {
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().rotate(rotation, 0, 0, 1);
        roundedRect(-width * .5, -height * .5, width, height, height * .5, color);
        api.getGLStateManager().popMatrix();
    }

    private enum IslandNoticeType {
        NONE,
        THEME,
        STYLE,
        SCALE,
        VOLUME,
        QUALITY,
        REFRESHING,
        REFRESH_SUCCESS,
        REFRESH_ERROR,
        PLAYLIST_TRACK_ADDING,
        PLAYLIST_TRACK_ADD_SUCCESS,
        PLAYLIST_TRACK_ALREADY_EXISTS,
        PLAYLIST_TRACK_ADD_ERROR,
        TRANSCODING,
        TRANSCODE_SUCCESS,
        PLAYBACK_ERROR,
        NETWORK_SUCCESS,
        NETWORK_ERROR,
        RECENT_PLAY_SUCCESS,
        RECENT_PLAY_ERROR,
        LISTENING_DURATION_SUCCESS,
        LISTENING_DURATION_ERROR
    }


}
