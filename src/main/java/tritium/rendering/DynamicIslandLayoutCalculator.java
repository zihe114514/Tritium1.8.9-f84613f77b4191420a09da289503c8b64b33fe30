package tritium.rendering;

import tritium.management.FontManager;
import tritium.rendering.DownloadDynamicIsland.DynamicIslandStyle;
import tritium.settings.HudConfig;

/** Calculates text-aware Dynamic Island bounds without mutating animation state. */
final class DynamicIslandLayoutCalculator {

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
        double minWidth;
        double targetHeight;
        if (style == DynamicIslandStyle.COMPACT) {
            minWidth = noticeMode ? 118.0 : 146.0;
            targetHeight = noticeMode ? 30.0 : 32.0;
        } else if (style == DynamicIslandStyle.CARD) {
            minWidth = noticeMode ? 146.0 : 170.0;
            targetHeight = noticeMode ? 38.0 : 42.0;
        } else if (systemCard) {
            minWidth = noticeMode ? 154.0 : 176.0;
            targetHeight = noticeMode ? 46.0 : 52.0;
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
        double sideReserve = systemCard ? 60.0 : (style == DynamicIslandStyle.CARD ? 48.0 : 46.0);
        if (!noticeMode) {
            sideReserve += Math.max(31.0, FontManager.pf12bold.getStringWidthD("100%")
                    * configuredTextScale + 14.0);
        }

        double screenMaxWidth = Math.max(compactWidth, (screenWidth - 12.0) / configuredScale);
        double configuredPreferredWidth = DynamicIslandMath.clamp(HudConfig.dynamicIslandMaxWidth, 160.0, 720.0);
        double desiredWidth = sideReserve + widestText * configuredTextScale;
        double maxWidth = Math.min(screenMaxWidth, Math.max(configuredPreferredWidth, desiredWidth));
        double safeMinWidth = Math.min(minWidth, maxWidth);
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