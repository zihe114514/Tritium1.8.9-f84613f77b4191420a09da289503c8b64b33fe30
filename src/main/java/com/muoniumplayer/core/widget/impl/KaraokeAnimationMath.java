package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.utils.math.Mth;

/**
 * Stateless easing and progress calculations shared by the OSD KTV renderer.
 *
 * <p>Keeping this math separate prevents rendering-state changes from altering
 * the configured karaoke timing curve.</p>
 */
final class KaraokeAnimationMath {

    private KaraokeAnimationMath() {
    }

    static double smoothStep(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    static double characterProgress(double characterTimeline, int characterIndex, float smoothing) {
        double raw = Math.max(0.0, Math.min(1.0, characterTimeline - characterIndex));
        double eased = smoothStep(raw);
        double clampedSmoothing = Math.max(0.0, Math.min(1.0, smoothing));
        return raw + (eased - raw) * clampedSmoothing;
    }

    static double wordProgress(LyricLine.Word word, float songProgress) {
        return smoothStep(Mth.limit(word.getProgress(songProgress), 0.0, 1.0));
    }

    /**
     * 单个字内「实心已唱段」的右边界。
     *
     * <p>KTV 填涂是逐字绘制的，但羽化窗口按整个 timed word 片段的前沿计算，所以前沿的软边
     * 可以横跨字与字的边界，不会在每个字里各自重新淡入一次。</p>
     *
     * @param front        填涂前沿相对本字左边缘的位置；小于 0 表示还没唱到，
     *                     大于 {@code textWidth} 表示已经唱过去了
     * @param featherWidth 羽化窗口宽度
     * @param textWidth    本字的布局宽度（未放大）
     */
    static double solidRight(double front, double featherWidth, double textWidth) {
        return Math.max(0.0, Math.min(front - featherWidth, textWidth));
    }

    /**
     * 羽化带该切成几条。
     *
     * <p>{@code ScissorClipManager} 把裁剪框向外取整到整屏幕像素，所以一条不足一个屏幕像素宽的
     * 羽化带会被撑大到一个像素。固定切十条时，前沿附近那十条会被撑成同一个一两像素宽的窄条并
     * 反复叠加同一个字形，叠出来的结果既过曝又糊。按"每条至少占两个屏幕像素"来定条数，就能让
     * 各条在取整之后仍然基本互不重叠。</p>
     *
     * @param screenScale 一个逻辑单位对应多少屏幕像素（GUI 缩放 × HUD 缩放 × 当前行缩放）
     */
    static int featherSteps(double featherWidth, double screenScale, int maxSteps) {
        if (featherWidth <= .01 || maxSteps <= 1) {
            return 1;
        }
        double pixels = featherWidth * Math.max(.5, screenScale);
        int steps = (int) Math.floor(pixels / 2.0);
        return Math.max(1, Math.min(maxSteps, steps));
    }

    /**
     * 求第 {@code step} 条羽化带落在本字内的区间，结果写入 {@code out[0]=left, out[1]=right}。
     *
     * <p>区间同时被实心段右边界和真实前沿夹住，因此所有实心段与羽化带在物理坐标里既不重叠
     * （不会叠加出更暗的接缝）也不留缝隙（覆盖恰好等于已唱区域）。</p>
     *
     * @return 该条是否有落在本字内的可见部分
     */
    static boolean featherStrip(double front, double featherWidth, double textWidth,
                                int step, int steps, double[] out) {
        double painted = Math.min(front, textWidth);
        double solid = solidRight(front, featherWidth, textWidth);
        double featherStart = front - featherWidth;
        out[0] = Math.max(featherStart + featherWidth * step / steps, solid);
        out[1] = Math.min(featherStart + featherWidth * (step + 1) / steps, painted);
        return out[1] - out[0] > .01;
    }
}