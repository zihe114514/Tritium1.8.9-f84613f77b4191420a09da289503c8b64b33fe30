package com.muoniumplayer.core.rendering;

import java.awt.Color;

/** Stateless calculations used by Dynamic Island layout, color and timing code. */
final class DynamicIslandMath {

    private DynamicIslandMath() {
    }

    static int parseNoticePercent(String value) {
        if (value == null) return 0;
        String digits = value.replaceAll("[^0-9]", "");
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(digits)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static long completionHoldMillis(float configuredSeconds) {
        return Math.round(clamp(configuredSeconds, .5, 6.0) * 1000.0);
    }

    static double smoothStep(double value) {
        double clamped = clamp01(value);
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    static float clamp01f(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static int clamp255(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    static int brightenAccent(int color, float amount) {
        float safeAmount = clamp01f(amount);
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        red = Math.round(red + (255 - red) * safeAmount);
        green = Math.round(green + (255 - green) * safeAmount);
        blue = Math.round(blue + (255 - blue) * safeAmount);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    /**
     * 把颜色沿色相环旋转指定角度，并保证饱和度与明度足够，用于液态玻璃边缘的动态取色光。
     * 主题强调色本身可能很暗（浅色主题），直接旋转会得到一条看不见的边。
     */
    static int shiftHue(int color, float degrees) {
        float[] hsb = Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null);
        float hue = (float) ((hsb[0] + degrees / 360.0f) % 1.0);
        if (hue < 0f) hue += 1.0f;
        float saturation = Math.max(.42f, Math.min(1f, hsb[1]));
        float brightness = Math.max(.82f, Math.min(1f, hsb[2]));
        return Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
    }

    static Color colorWithAlpha(int color, float alpha) {
        return new Color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF,
                clamp255(clamp01f(alpha) * 255f));
    }
}