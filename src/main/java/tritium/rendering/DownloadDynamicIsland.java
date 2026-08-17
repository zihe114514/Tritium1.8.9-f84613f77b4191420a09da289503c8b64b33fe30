package tritium.rendering;

import org.lwjgl.opengl.GL11;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.screens.ncm.NCMTheme;
import tritium.settings.HudConfig;

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

    private static final long NOTICE_HOLD_MS = 1650L;
    private static final double EXPANDED_WIDTH = 178.0;
    private static final double EXPANDED_HEIGHT = 38.0;
    private static final double NOTICE_WIDTH = 154.0;
    private static final double NOTICE_HEIGHT = 34.0;
    private static final double COMPACT_WIDTH = 40.0;
    private static final double COMPACT_HEIGHT = 16.0;
    private static final double TOP_MARGIN = 6.0;

    /** Modern island silhouettes inspired by pill, glass, compact status and card surfaces. */
    public enum DynamicIslandStyle {
        PILL("经典胶囊"),
        GLASS("通透玻璃"),
        COMPACT("紧凑状态"),
        CARD("浮层卡片");

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
        downloadProgress = clamp01(progress);
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
        double rawProgress = clamp01(downloadProgress);
        boolean activeDownload = downloading;
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
            if (activeDownload) {
                shownAt = sourceStartedAt > 0L ? sourceStartedAt : now;
            } else if (sourceCompletedAt > 0L && now - sourceCompletedAt < getCompletionHoldMs()) {
                completing = true;
                completeAt = sourceCompletedAt;
                animatedProgress = 1.0;
            } else if (sourceNoticeType != IslandNoticeType.NONE
                    && (sourceNoticePersistent || now - sourceNoticeShownAt < NOTICE_HOLD_MS)) {
                shownAt = sourceNoticeShownAt;
            }
        }

        boolean noticeChanged = sourceNoticeRevision != observedNoticeRevision;
        if (noticeChanged) {
            observedNoticeRevision = sourceNoticeRevision;
            if (!activeDownload) {
                completing = false;
                successMorph = 0f;
                shownAt = sourceNoticeShownAt > 0L ? sourceNoticeShownAt : now;
            }
        }

        boolean progressRestarted = activeDownload && rawProgress + .12 < lastObservedProgress;
        boolean justStarted = activeDownload && !previousDownloading;
        if (justStarted || progressRestarted) {
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
                && now - sourceCompletedAt < getCompletionHoldMs();
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

        boolean holdingCompletion = completing && now - completeAt < getCompletionHoldMs();
        boolean activeNotice = sourceNoticeType != IslandNoticeType.NONE
                && sourceNoticeShownAt > 0L
                && now >= sourceNoticeShownAt
                && (sourceNoticePersistent || now - sourceNoticeShownAt < NOTICE_HOLD_MS);
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

        final double easedExpansion = smoothStep(expansion);
        final float alpha = clamp01f(visibility);
        final String speed = downloadSpeed;
        final boolean renderNotice = noticeMode;
        final IslandNoticeType renderedNoticeType = sourceNoticeType;
        final String renderedNoticeTitle = sourceNoticeTitle;
        final String renderedNoticeValue = sourceNoticeValue;
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                drawIsland(easedExpansion, alpha, animatedProgress, successMorph, speed,
                        System.currentTimeMillis(), shownAt, false, renderNotice,
                        renderedNoticeType, renderedNoticeTitle, renderedNoticeValue,
                        sourceNoticePersistent);
            }
        });
    }

    private void renderPreviewInternal() {
        if (RenderSystem.getWidth() <= 0 || RenderSystem.getHeight() <= 0) return;
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                float alpha = HudConfig.dynamicIslandEnabled ? .96f : .46f;
                drawIsland(1.0, alpha, .64, 0f,
                        HudConfig.dynamicIslandEnabled ? "1.8 MB/s" : "灵动岛已关闭",
                        System.currentTimeMillis(), 0L, true, false,
                        IslandNoticeType.NONE, "", "", false);
            }
        });
    }

    private void renderIsolated(Runnable renderer) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        api.getGLStateManager().pushMatrix();
        try {
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

    private void drawIsland(double expansionValue, float alpha, double progress, float success,
                            String speedValue, long now, long animationStart, boolean preview,
                            boolean noticeMode, IslandNoticeType activeNoticeType,
                            String activeNoticeTitle, String activeNoticeValue,
                            boolean activeNoticePersistent) {
        double configuredScale = clamp(HudConfig.dynamicIslandScale, .60, 1.35);
        double screenWidth = RenderSystem.getWidth();
        DynamicIslandStyle style = getStyle();
        double compactWidth = style == DynamicIslandStyle.COMPACT ? 46.0 : COMPACT_WIDTH;
        double compactHeight = style == DynamicIslandStyle.COMPACT ? 14.0 : COMPACT_HEIGHT;
        double targetWidth = noticeMode ? NOTICE_WIDTH : EXPANDED_WIDTH;
        double targetHeight = noticeMode ? NOTICE_HEIGHT : EXPANDED_HEIGHT;
        if (style == DynamicIslandStyle.COMPACT) {
            targetWidth = noticeMode ? 142.0 : 164.0;
            targetHeight = noticeMode ? 30.0 : 32.0;
        } else if (style == DynamicIslandStyle.CARD) {
            targetWidth = noticeMode ? 166.0 : 196.0;
            targetHeight = noticeMode ? 38.0 : 42.0;
        }
        double maxLogicalWidth = Math.max(compactWidth, (screenWidth - 12.0) / configuredScale);
        double expandedWidth = Math.min(targetWidth, maxLogicalWidth);
        double width = lerp(compactWidth, expandedWidth, expansionValue);
        double height = lerp(compactHeight, targetHeight, expansionValue);
        double centerX = screenWidth * .5;
        double visibilityEase = smoothStep(alpha);
        double y = TOP_MARGIN - (height + 11.0) * (1.0 - visibilityEase);

        double entryScale = 1.0;
        if (!preview && animationStart > 0L) {
            double entryProgress = clamp01((now - animationStart) / 420.0);
            double cubicOut = 1.0 - Math.pow(1.0 - entryProgress, 3.0);
            double restrainedOvershoot = Math.sin(entryProgress * Math.PI)
                    * (1.0 - entryProgress) * .025;
            entryScale = .965 + .035 * cubicOut + restrainedOvershoot;
        }

        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, TOP_MARGIN, configuredScale);
        scaleAtPos(centerX, y + height * .5, entryScale);

        double x = centerX - width * .5;
        double radius = style == DynamicIslandStyle.CARD ? Math.min(12.0, height * .30) : height * .5;
        int accentColor = NCMTheme.getAccentColor();

        // Every style shares the same animated bounds; only the surface treatment changes.
        roundedRect(x - 2.5, y + 1.5, width + 5.0, height + 4.0, radius + 2.5,
                hexColor(0f, 0f, 0f, alpha * (style == DynamicIslandStyle.GLASS ? .18f : .26f)));
        if (style == DynamicIslandStyle.GLASS) {
            roundedRect(x, y, width, height, radius, RenderSystem.reAlpha(accentColor, alpha * .20f));
            roundedRect(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height * .46), Math.max(1.0, radius - 1.0), hexColor(1f, 1f, 1f, alpha * .075f));
            roundedOutline(x, y, width, height, radius, .75, new Color(255, 255, 255, clamp255(alpha * 62f)));
        } else if (style == DynamicIslandStyle.CARD) {
            roundedRect(x, y, width, height, radius, hexColor(.028f, .034f, .046f, alpha * .985f));
            roundedRect(x, y + radius, 2.2, Math.max(1.0, height - radius * 2.0), 1.1, RenderSystem.reAlpha(accentColor, alpha * .90f));
            roundedOutline(x, y, width, height, radius, .65, new Color(255, 255, 255, clamp255(alpha * 30f)));
        } else {
            roundedRect(x, y, width, height, radius, hexColor(.012f, .014f, .020f, alpha * .985f));
            roundedOutline(x, y, width, height, radius, .65, new Color(255, 255, 255, clamp255(alpha * 20f)));
        }
        roundedRect(x + radius * .70, y + .8, Math.max(2.0, width - radius * 1.40), .75, .38,
                hexColor(1f, 1f, 1f, alpha * (style == DynamicIslandStyle.GLASS ? .13f : .055f)));

        double iconInset = style == DynamicIslandStyle.CARD ? 22.0 : 19.0;
        double iconExpandedX = centerX - expandedWidth * .5 + iconInset;
        double iconX = lerp(centerX, iconExpandedX, expansionValue);
        double iconY = y + height * .5;
        double pulse = .5 + .5 * Math.sin(now / 260.0);
        float iconAlpha = alpha * (.76f + (float) pulse * .16f);

        // Keep the left activity mark visually dominant across every style.
        double iconScale = 1.16;
        api.getGLStateManager().pushMatrix();
        scaleAtPos(iconX, iconY, iconScale);
        roundedRect(iconX - 8.3, iconY - 8.3, 16.6, 16.6, 8.3,
                RenderSystem.reAlpha(accentColor, iconAlpha * .14f));
        roundedOutline(iconX - 8.3, iconY - 8.3, 16.6, 16.6, 8.3, .65,
                new Color(255, 255, 255, clamp255(iconAlpha * 46f)));
        if (noticeMode) {
            renderNoticeIcon(activeNoticeType, iconX, iconY, iconAlpha, accentColor, now);
        } else {
            renderSpinner(iconX, iconY, iconAlpha * (1f - success), accentColor, now, preview);
            renderSuccess(iconX, iconY, alpha * success);
        }
        api.getGLStateManager().popMatrix();

        float textAlpha = alpha * contentAlpha * (float) expansionValue;
        if (preview) textAlpha = alpha * (float) expansionValue;
        if (textAlpha > .01f) {
            double textScale = clamp(HudConfig.dynamicIslandTextScale, .75, 1.35);
            double textLeft = x + 30.0;
            double textRight = x + width - 9.0;
            double textCenter = (textLeft + textRight) * .5;
            double textPivotY = y + height * .5;

            // Scale only the typography layer; the island geometry remains stable.
            // This keeps the configured font size from changing the progress track
            // or causing the surface to jump between styles.
            api.getGLStateManager().pushMatrix();
            scaleAtPos(textCenter, textPivotY, textScale);
            if (noticeMode) {
                String title = safeNoticeValue(activeNoticeTitle, "状态");
                String value = safeNoticeValue(activeNoticeValue, "—");
                double availableWidth = Math.max(24.0, textRight - textLeft);
                drawCenteredIslandText(FontManager.pf12, title, textCenter, y + 5.4,
                        availableWidth, textScale, hexColor(.58f, .61f, .68f, textAlpha * .96f));
                drawCenteredIslandText(FontManager.pf14bold, value, textCenter, y + 16.6,
                        availableWidth, textScale, hexColor(1f, 1f, 1f, textAlpha));
            } else {
                String normalizedSpeed = speedValue == null ? "" : speedValue.trim();
                String subtitle = preview
                        ? normalizedSpeed
                        : (normalizedSpeed.isEmpty() || "0 b/s".equalsIgnoreCase(normalizedSpeed)
                        ? "正在连接音频源" : normalizedSpeed);
                String title = preview ? "灵动岛预览" : "正在加载歌曲";
                float loadingAlpha = textAlpha * (1f - success);
                float completeAlpha = textAlpha * success;
                String status = success > .52f ? "完成" : Math.max(0, Math.min(100,
                        (int) Math.round(clamp01(progress) * 100.0))) + "%";
                double statusWidth = Math.max(25.0, FontManager.pf12bold.getStringWidthD(status) + 10.0);
                double statusX = textRight - statusWidth;
                double bodyRight = statusX - 5.0;
                double bodyCenter = (textLeft + bodyRight) * .5;
                double bodyWidth = Math.max(28.0, bodyRight - textLeft);

                drawCenteredIslandText(FontManager.pf14bold, title, bodyCenter, y + 5.1,
                        bodyWidth, textScale, hexColor(1f, 1f, 1f, loadingAlpha));
                drawCenteredIslandText(FontManager.pf14bold, "加载完成", bodyCenter, y + 5.1,
                        bodyWidth, textScale, hexColor(1f, 1f, 1f, completeAlpha));
                drawCenteredIslandText(FontManager.pf12, subtitle, bodyCenter, y + 17.0,
                        bodyWidth, textScale, hexColor(.62f, .65f, .72f, loadingAlpha * .94f));
                drawCenteredIslandText(FontManager.pf12, "可以开始播放", bodyCenter, y + 17.0,
                        bodyWidth, textScale, hexColor(.59f, .90f, .67f, completeAlpha));

                int statusColor = success > .52f
                        ? hexColor(.58f, 1f, .68f, textAlpha)
                        : hexColor(.82f, .85f, .91f, textAlpha);
                roundedRect(statusX, y + 4.2, statusWidth, 12.5, 6.25,
                        success > .52f ? hexColor(.18f, .55f, .32f, textAlpha * .34f)
                                : hexColor(1f, 1f, 1f, textAlpha * .10f));
                drawCenteredIslandText(FontManager.pf12bold, status, statusX + statusWidth * .5, y + 6.1,
                        statusWidth - 4.0, textScale, statusColor);

                double progressX = x + 9.0;
                double progressY = y + height - 3.7;
                double progressWidth = width - 18.0;
                double progressHeight = Math.min(height * .22,
                        clamp(HudConfig.dynamicIslandProgressHeight, .75, 4.0));
                double progressRadius = progressHeight * .5;
                roundedRect(progressX, progressY, progressWidth, progressHeight, progressRadius,
                        hexColor(1f, 1f, 1f, textAlpha * .10f));
                double fillWidth = progressWidth * clamp01(progress);
                if (fillWidth > .10) {
                    Color startColor = colorWithAlpha(accentColor, textAlpha * .94f);
                    Color endColor = new Color(103, 216, 255, clamp255(textAlpha * 255f));
                    roundedRectGradientHorizontal(progressX, progressY, fillWidth, progressHeight,
                            Math.min(progressRadius, fillWidth * .5), startColor, endColor);
                }
            }
            api.getGLStateManager().popMatrix();
        }
        api.getGLStateManager().popMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private void drawCenteredIslandText(CFontRenderer font, String text, double centerX, double y,
                                         double maxWidth, double textScale, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        double safeScale = clamp(textScale, .75, 1.35);
        String trimmed = font.trim(text, Math.max(10.0, maxWidth / safeScale));
        double textWidth = font.getStringWidthD(trimmed);
        font.drawString(trimmed, centerX - textWidth * .5, y, color);
    }

    private void renderNoticeIcon(IslandNoticeType type, double centerX, double centerY,
                                  float alpha, int accentColor, long now) {
        if (alpha <= .01f) return;
        int foreground = hexColor(.94f, .95f, .98f, alpha);
        if (type == IslandNoticeType.REFRESHING || type == IslandNoticeType.PLAYLIST_TRACK_ADDING) {
            renderSpinner(centerX, centerY, alpha, accentColor, now, false);
            return;
        }
        if (type == IslandNoticeType.REFRESH_SUCCESS
                || type == IslandNoticeType.PLAYLIST_TRACK_ADD_SUCCESS
                || type == IslandNoticeType.PLAYLIST_TRACK_ALREADY_EXISTS) {
            drawRotatedPill(centerX - 1.8, centerY + .8, 3.8, 1.25, 43f,
                    hexColor(.70f, 1f, .77f, alpha));
            drawRotatedPill(centerX + 1.6, centerY - .4, 6.2, 1.25, -47f,
                    hexColor(.70f, 1f, .77f, alpha));
            return;
        }
        if (type == IslandNoticeType.REFRESH_ERROR || type == IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR) {
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

    private void renderSuccess(double centerX, double centerY, float alpha) {
        if (alpha <= .01f) return;
        double scale = .78 + .22 * smoothStep(successMorph);
        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, centerY, scale);
        roundedOutline(centerX - 6.2, centerY - 6.2, 12.4, 12.4, 6.2, .75,
                new Color(91, 240, 129, clamp255(alpha * 220f)));
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
        QUALITY,
        REFRESHING,
        REFRESH_SUCCESS,
        REFRESH_ERROR,
        PLAYLIST_TRACK_ADDING,
        PLAYLIST_TRACK_ADD_SUCCESS,
        PLAYLIST_TRACK_ALREADY_EXISTS,
        PLAYLIST_TRACK_ADD_ERROR
    }

    private static long getCompletionHoldMs() {
        return Math.round(clamp(HudConfig.dynamicIslandCompletionHoldSeconds, .5, 6.0) * 1000.0);
    }

    private static double smoothStep(double value) {
        double clamped = clamp01(value);
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static float clamp01f(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp255(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static Color colorWithAlpha(int color, float alpha) {
        return new Color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF,
                clamp255(clamp01f(alpha) * 255f));
    }
}
