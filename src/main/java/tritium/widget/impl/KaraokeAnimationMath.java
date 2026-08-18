package tritium.widget.impl;

import tritium.screens.ncm.LyricLine;
import tritium.utils.math.Mth;

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
}