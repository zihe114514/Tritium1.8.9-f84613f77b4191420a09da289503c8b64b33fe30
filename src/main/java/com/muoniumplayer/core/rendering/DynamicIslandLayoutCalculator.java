package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland.DynamicIslandStyle;
import com.muoniumplayer.core.settings.HudConfig;

/** Calculates text-aware Dynamic Island bounds without mutating animation state. */
final class DynamicIslandLayoutCalculator {

    /** Reference base width the per-style minimum widths were tuned against. */
    static final double DEFAULT_BASE_WIDTH = 250.0;
    static final double MIN_BASE_WIDTH = 160.0;
    static final double MAX_BASE_WIDTH = 720.0;

    private DynamicIslandLayoutCalculator() {
    }

    static LayoutData calculate(DynamicIslandStyle style, boolean noticeMode, boolean volumeNotice,
                                String noticeTitleValue, String noticeBodyValue, String speedValue,
                                boolean preview, double configuredScale, double screenWidth,
                                double compactWidth, double minAutoTextScale, double maxAutoTextScale) {
        String title;
        String value;
        if (noticeMode) {
            title = safeValue(noticeTitleValue, "状态");
            value = safeValue(noticeBodyValue, "—");
        } else {
            String speed = speedValue == null ? "" : speedValue.trim();
            title = preview ? "灵动岛预览" : "正在加载歌曲";
            value = preview ? speed : (speed.isEmpty() || "0 b/s".equalsIgnoreCase(speed)
                    ? "正在连接音频源" : speed);
        }

        boolean systemCard = style == DynamicIslandStyle.SYSTEM_CARD;
        boolean musicFocus = style == DynamicIslandStyle.MUSIC_FOCUS;
        double minWidth;
        double targetHeight;
        if (style == DynamicIslandStyle.COMPACT) {
            minWidth = noticeMode ? 118.0 : 146.0;
            targetHeight = noticeMode ? 30.0 : 32.0;
        } else if (style == DynamicIslandStyle.CARD) {
            minWidth = noticeMode ? 146.0 : 170.0;
            targetHeight = noticeMode ? 38.0 : 42.0;
        } else if (musicFocus) {
            minWidth = noticeMode ? 172.0 : 194.0;
            targetHeight = noticeMode ? 50.0 : 56.0;
        } else if (systemCard) {
            minWidth = noticeMode ? 154.0 : 176.0;
            targetHeight = noticeMode ? 46.0 : 52.0;
        } else if (style == DynamicIslandStyle.LIQUID_GLASS) {
            // 液态玻璃要给折射亮带和边缘光留出余量，所以比通透玻璃再高一点、宽一点。
            minWidth = noticeMode ? 142.0 : 168.0;
            targetHeight = noticeMode ? 38.0 : 42.0;
        } else if (style == DynamicIslandStyle.GLASS) {
            minWidth = noticeMode ? 132.0 : 158.0;
            targetHeight = noticeMode ? 34.0 : 38.0;
        } else {
            minWidth = noticeMode ? 126.0 : 152.0;
            targetHeight = noticeMode ? 34.0 : 38.0;
        }

        if (volumeNotice) {
            targetHeight += systemCard ? 8.0 : 7.0;
        }

        double titleWidth = FontManager.pf12.getStringWidthD(title);
        double valueWidth = FontManager.pf14bold.getStringWidthD(value);
        double widestText = Math.max(titleWidth, valueWidth);
        double configuredTextScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandTextScale,
                minAutoTextScale, maxAutoTextScale);
        double sideReserve = systemCard ? 60.0 : (musicFocus ? 68.0
                : (style == DynamicIslandStyle.LIQUID_GLASS ? 50.0
                : (style == DynamicIslandStyle.CARD ? 48.0 : 46.0)));
        if (!noticeMode) {
            sideReserve += Math.max(31.0, FontManager.pf12bold.getStringWidthD("100%")
                    * configuredTextScale + 14.0);
        }

        double screenMaxWidth = Math.max(compactWidth, (screenWidth - 12.0) / configuredScale);
        // "灵动岛基础宽度" is a real base width, not a passive ceiling. Previously it was only
        // fed into Math.max(configured, desired), so the natural text width always won and dragging
        // the slider changed nothing. Now the configured value scales the per-style minimum width and
        // caps how far copy may stretch the island, so both directions of the slider are visible.
        double configuredBaseWidth = DynamicIslandMath.clamp(HudConfig.dynamicIslandMaxWidth,
                MIN_BASE_WIDTH, MAX_BASE_WIDTH);
        double baseWidthRatio = configuredBaseWidth / DEFAULT_BASE_WIDTH;
        double scaledMinWidth = minWidth * baseWidthRatio;
        double desiredWidth = sideReserve + widestText * configuredTextScale;
        double maxWidth = Math.min(screenMaxWidth, Math.max(scaledMinWidth, configuredBaseWidth));
        double safeMinWidth = Math.min(scaledMinWidth, maxWidth);
        double textScale = configuredTextScale;
        if (widestText > .01) {
            double availableAtMax = Math.max(28.0, maxWidth - sideReserve);
            textScale = Math.min(textScale, Math.max(minAutoTextScale, availableAtMax / widestText));
        }
        double targetWidth = DynamicIslandMath.clamp(sideReserve + widestText * textScale, safeMinWidth, maxWidth);
        return new LayoutData(title, value, textScale, targetWidth, targetHeight);
    }

    private static String safeValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    static final class LayoutData {
        final String title;
        final String value;
        final double textScale;
        final double targetWidth;
        final double targetHeight;

        private LayoutData(String title, String value, double textScale, double targetWidth, double targetHeight) {
            this.title = title;
            this.value = value;
            this.textScale = textScale;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
        }
    }
}