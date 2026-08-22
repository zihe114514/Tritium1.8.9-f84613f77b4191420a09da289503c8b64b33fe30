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
        double minWidth = styleMinWidth(style, noticeMode);
        double targetHeight = styleTargetHeight(style, noticeMode);

        if (volumeNotice) {
            targetHeight += systemCard ? 8.0 : 7.0;
        }

        double titleWidth = FontManager.pf12.getStringWidthD(title);
        double valueWidth = FontManager.pf14bold.getStringWidthD(value);
        double widestText = Math.max(titleWidth, valueWidth);
        double configuredTextScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandTextScale,
                minAutoTextScale, maxAutoTextScale);
        double sideReserve = styleSideReserve(style);
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

    /**
     * 常驻状态的尺寸。常驻卡片与通知卡片都是「左图标 + 一行内容」的同一种版式,
     * 因此直接复用通知态的最小宽度与高度表,不再维护第二套数字。
     *
     * @param contentWidth 已按固定位数模板量好的常驻内容宽度,数值跳动不会改变它
     */
    static LayoutData calculateAmbient(DynamicIslandStyle style, double contentWidth,
                                       double configuredScale, double screenWidth, double compactWidth) {
        double minWidth = styleMinWidth(style, true);
        double targetHeight = styleTargetHeight(style, true);
        double sideReserve = styleSideReserve(style);
        double screenMaxWidth = Math.max(compactWidth, (screenWidth - 12.0) / configuredScale);
        double configuredBaseWidth = DynamicIslandMath.clamp(HudConfig.dynamicIslandMaxWidth,
                MIN_BASE_WIDTH, MAX_BASE_WIDTH);
        double scaledMinWidth = minWidth * (configuredBaseWidth / DEFAULT_BASE_WIDTH);
        double maxWidth = Math.min(screenMaxWidth, Math.max(scaledMinWidth, configuredBaseWidth));
        double safeMinWidth = Math.min(scaledMinWidth, maxWidth);
        double targetWidth = DynamicIslandMath.clamp(sideReserve + Math.max(0.0, contentWidth),
                safeMinWidth, maxWidth);
        return new LayoutData("", "", 1.0, targetWidth, targetHeight);
    }

    /** 常驻内容可用的横向空间,渲染层据此决定要不要丢掉优先级最低的条目。 */
    static double ambientContentBudget(DynamicIslandStyle style, double configuredScale, double screenWidth) {
        double screenMaxWidth = Math.max(60.0, (screenWidth - 12.0) / configuredScale);
        double configuredBaseWidth = DynamicIslandMath.clamp(HudConfig.dynamicIslandMaxWidth,
                MIN_BASE_WIDTH, MAX_BASE_WIDTH);
        double maxWidth = Math.min(screenMaxWidth, configuredBaseWidth);
        return Math.max(24.0, maxWidth - styleSideReserve(style));
    }

    private static double styleMinWidth(DynamicIslandStyle style, boolean noticeMode) {
        if (style == DynamicIslandStyle.COMPACT) return noticeMode ? 118.0 : 146.0;
        if (style == DynamicIslandStyle.CARD) return noticeMode ? 146.0 : 170.0;
        if (style == DynamicIslandStyle.MUSIC_FOCUS) return noticeMode ? 172.0 : 194.0;
        if (style == DynamicIslandStyle.SYSTEM_CARD) return noticeMode ? 154.0 : 176.0;
        // 液态玻璃要给折射亮带和边缘光留出余量,所以比通透玻璃再宽一点。
        if (style == DynamicIslandStyle.LIQUID_GLASS) return noticeMode ? 142.0 : 168.0;
        if (style == DynamicIslandStyle.GLASS) return noticeMode ? 132.0 : 158.0;
        return noticeMode ? 126.0 : 152.0;
    }

    private static double styleTargetHeight(DynamicIslandStyle style, boolean noticeMode) {
        if (style == DynamicIslandStyle.COMPACT) return noticeMode ? 30.0 : 32.0;
        if (style == DynamicIslandStyle.CARD) return noticeMode ? 38.0 : 42.0;
        if (style == DynamicIslandStyle.MUSIC_FOCUS) return noticeMode ? 50.0 : 56.0;
        if (style == DynamicIslandStyle.SYSTEM_CARD) return noticeMode ? 46.0 : 52.0;
        if (style == DynamicIslandStyle.LIQUID_GLASS) return noticeMode ? 38.0 : 42.0;
        if (style == DynamicIslandStyle.GLASS) return noticeMode ? 34.0 : 38.0;
        return noticeMode ? 34.0 : 38.0;
    }

    /** 左侧图标托盘与右侧留白合计要占掉的宽度。 */
    private static double styleSideReserve(DynamicIslandStyle style) {
        if (style == DynamicIslandStyle.SYSTEM_CARD) return 60.0;
        if (style == DynamicIslandStyle.MUSIC_FOCUS) return 68.0;
        if (style == DynamicIslandStyle.LIQUID_GLASS) return 50.0;
        if (style == DynamicIslandStyle.CARD) return 48.0;
        return 46.0;
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