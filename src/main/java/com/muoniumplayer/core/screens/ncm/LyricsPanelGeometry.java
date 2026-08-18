package com.muoniumplayer.core.screens.ncm;

/** Stateless viewport, timing and sizing calculations for the full-screen lyric panel. */
final class LyricsPanelGeometry {

    private LyricsPanelGeometry() {
    }

    static double contentScale(float alpha) {
        return .96 + (Math.max(0.0f, Math.min(1.0f, alpha)) * .04);
    }

    static double smoothKaraokeProgress(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    static double characterKaraokeProgress(double wordProgress, int characterIndex) {
        return smoothKaraokeProgress(wordProgress - characterIndex);
    }

    static double lyricLineSpacing() {
        return 20;
    }

    static double lyricAnchorFraction() {
        return .25;
    }

    static Viewport createViewport(double posX, double posY, double width, double height, double borderThickness) {
        double horizontalInset = Math.max(8.0, width * .04);
        double verticalInset = Math.max(borderThickness, Math.min(10.0, height * .018));
        double left = posX + width * .48;
        double right = Math.max(left, posX + width - horizontalInset);
        double top = posY + verticalInset;
        double bottom = Math.max(top, posY + height - verticalInset);
        return new Viewport(left, top, right - left, bottom - top);
    }

    static double coverSizeMax(double width, double height) {
        return Math.min(height * .46, width * .36);
    }

    static String formatDuration(float totalMillis) {
        float totalSeconds = totalMillis / 1000;
        float hours = totalSeconds / 3600;
        float minutes = (totalSeconds % 3600) / 60;
        float seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();
        if ((int) hours > 0) {
            builder.append(String.format("%02d:", (int) hours));
        }
        builder.append(String.format("%02d:", (int) minutes));
        builder.append(String.format("%02d", (int) seconds));
        return builder.toString();
    }

    static final class Viewport {
        final double left;
        final double top;
        final double width;
        final double height;

        private Viewport(double left, double top, double width, double height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }
    }
}