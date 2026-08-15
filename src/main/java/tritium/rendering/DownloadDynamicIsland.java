package tritium.rendering;

import org.lwjgl.opengl.GL11;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
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

    private static final long COMPLETE_HOLD_MS = 900L;
    private static final double EXPANDED_WIDTH = 196.0;
    private static final double EXPANDED_HEIGHT = 42.0;
    private static final double COMPACT_WIDTH = 46.0;
    private static final double COMPACT_HEIGHT = 18.0;
    private static final double TOP_MARGIN = 6.0;

    private static volatile boolean downloading;
    private static volatile double downloadProgress;
    private static volatile String downloadSpeed = "0 b/s";
    private static volatile long downloadStartedAt;
    private static volatile long downloadCompletedAt;

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

        if (!initialized) {
            initialized = true;
            previousDownloading = activeDownload;
            lastObservedProgress = rawProgress;
            animatedProgress = activeDownload ? rawProgress : 0.0;
            if (activeDownload) {
                shownAt = sourceStartedAt > 0L ? sourceStartedAt : now;
            } else if (sourceCompletedAt > 0L && now - sourceCompletedAt < COMPLETE_HOLD_MS) {
                completing = true;
                completeAt = sourceCompletedAt;
                animatedProgress = 1.0;
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
                && now - sourceCompletedAt < COMPLETE_HOLD_MS;
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

        boolean holdingCompletion = completing && now - completeAt < COMPLETE_HOLD_MS;
        boolean enabled = HudConfig.dynamicIslandEnabled;
        boolean shouldShow = enabled && (activeDownload || holdingCompletion);

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
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                drawIsland(easedExpansion, alpha, animatedProgress, successMorph, speed,
                        System.currentTimeMillis(), shownAt, false);
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
                        System.currentTimeMillis(), 0L, true);
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
                            String speedValue, long now, long animationStart, boolean preview) {
        double configuredScale = clamp(HudConfig.dynamicIslandScale, .60, 1.35);
        double screenWidth = RenderSystem.getWidth();
        double maxLogicalWidth = Math.max(COMPACT_WIDTH, (screenWidth - 12.0) / configuredScale);
        double expandedWidth = Math.min(EXPANDED_WIDTH, maxLogicalWidth);
        double width = lerp(COMPACT_WIDTH, expandedWidth, expansionValue);
        double height = lerp(COMPACT_HEIGHT, EXPANDED_HEIGHT, expansionValue);
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
        double radius = height * .5;
        int accentColor = NCMTheme.getAccentColor();

        // Small layered shadows preserve separation without the oversized glow of the old panel.
        roundedRect(x - 2.5, y + 1.5, width + 5.0, height + 4.0, radius + 2.5,
                hexColor(0f, 0f, 0f, alpha * .26f));
        roundedRect(x, y, width, height, radius,
                hexColor(.012f, .014f, .020f, alpha * .985f));
        roundedOutline(x, y, width, height, radius, .65,
                new Color(255, 255, 255, clamp255(alpha * 20f)));
        roundedRect(x + radius * .70, y + .8, Math.max(2.0, width - radius * 1.40), .75, .38,
                hexColor(1f, 1f, 1f, alpha * .055f));

        double iconExpandedX = centerX - expandedWidth * .5 + 19.0;
        double iconX = lerp(centerX, iconExpandedX, expansionValue);
        double iconY = y + height * .5;
        double pulse = .5 + .5 * Math.sin(now / 260.0);
        float iconAlpha = alpha * (.76f + (float) pulse * .16f);

        roundedRect(iconX - 8.0, iconY - 8.0, 16.0, 16.0, 8.0,
                RenderSystem.reAlpha(accentColor, iconAlpha * .085f));
        renderSpinner(iconX, iconY, iconAlpha * (1f - success), accentColor, now, preview);
        renderSuccess(iconX, iconY, alpha * success);

        float textAlpha = alpha * contentAlpha * (float) expansionValue;
        if (preview) textAlpha = alpha * (float) expansionValue;
        if (textAlpha > .01f) {
            double textX = x + 34.0;
            double titleY = y + 7.0;
            double subtitleY = y + 20.0;
            float loadingAlpha = textAlpha * (1f - success);
            float completeAlpha = textAlpha * success;

            FontManager.pf14bold.drawString(preview ? "灵动岛预览" : "正在加载歌曲", textX, titleY,
                    hexColor(1f, 1f, 1f, loadingAlpha));
            FontManager.pf14bold.drawString("加载完成", textX, titleY,
                    hexColor(1f, 1f, 1f, completeAlpha));

            String normalizedSpeed = speedValue == null ? "" : speedValue.trim();
            String subtitle;
            if (preview) {
                subtitle = normalizedSpeed;
            } else {
                subtitle = normalizedSpeed.isEmpty() || "0 b/s".equalsIgnoreCase(normalizedSpeed)
                        ? "正在连接音频源" : normalizedSpeed;
            }
            FontManager.pf12.drawString(subtitle, textX, subtitleY,
                    hexColor(.66f, .69f, .76f, loadingAlpha * .94f));
            FontManager.pf12.drawString("可以开始播放", textX, subtitleY,
                    hexColor(.59f, .90f, .67f, completeAlpha));

            String status = success > .52f ? "完成" : Math.max(0, Math.min(100,
                    (int) Math.round(clamp01(progress) * 100.0))) + "%";
            double statusX = x + width - 11.0 - FontManager.pf12bold.getStringWidthD(status);
            FontManager.pf12bold.drawString(status, statusX, titleY + .5,
                    success > .52f
                            ? hexColor(.58f, 1f, .68f, textAlpha)
                            : hexColor(.85f, .88f, .94f, textAlpha));

            double progressX = x + 10.0;
            double progressY = y + height - 4.2;
            double progressWidth = width - 20.0;
            double progressHeight = 1.6;
            roundedRect(progressX, progressY, progressWidth, progressHeight, .8,
                    hexColor(1f, 1f, 1f, textAlpha * .10f));
            double fillWidth = progressWidth * clamp01(progress);
            if (fillWidth > .10) {
                Color start = colorWithAlpha(accentColor, textAlpha * .94f);
                Color end = new Color(103, 216, 255, clamp255(textAlpha * 255f));
                roundedRectGradientHorizontal(progressX, progressY, fillWidth, progressHeight,
                        Math.min(.8, fillWidth * .5), start, end);
            }
        }

        api.getGLStateManager().popMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
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
            roundedRect(-.65, -6.0, 1.3, 2.5, .65,
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
