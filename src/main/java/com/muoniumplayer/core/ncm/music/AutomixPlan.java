package com.muoniumplayer.core.ncm.music;

/**
 * A planned automix handover. Positions are absolute milliseconds inside their own track.
 *
 * <p>{@link #fireMillis} is where the outgoing deck starts fading, {@link #incomingCueMillis} is where
 * the incoming deck starts playing, and both ramps last {@link #overlapMillis}. The ramp is equal power
 * ({@code cos}/{@code sin}), so perceived loudness stays flat through the blend instead of dipping in
 * the middle the way a linear fade does.</p>
 *
 * <p>On top of the volume ramp the plan carries the two things that separate a DJ blend from a
 * crossfade: a <b>bass swap</b> ({@link #outgoingBassDb}/{@link #incomingBassDb}), which keeps the low
 * band belonging to exactly one track at a time, and an optional <b>tempo lock</b>
 * ({@link #outgoingRate}), which bends the outgoing deck onto the incoming tempo so the two beat grids
 * do not drift apart while they are audible together.</p>
 */
final class AutomixPlan {

    /** The outgoing low band is fully out of the way by this point in the ramp. */
    private static final double BASS_CUT_COMPLETE = .5;
    /** The incoming low band stays held back until here, then comes up. */
    private static final double BASS_ENTRY_START = .3;
    private static final double BASS_ENTRY_COMPLETE = .8;
    /**
     * The tempo bend is applied over the first sliver of the ramp. Instantly would be a step in pitch;
     * slowly would let the grids drift for exactly as long as the correction takes to arrive.
     */
    private static final double RATE_RAMP_COMPLETE = .15;

    final long fireMillis;
    final long overlapMillis;
    final long incomingCueMillis;
    /** Loudness match for the incoming deck, applied only during the blend. */
    final float matchGain;
    final boolean barAligned;
    /** Whether the low band is swapped between the decks during the blend. */
    final boolean bassSwap;
    /**
     * Playback rate the outgoing deck is bent to, 1 meaning "leave it alone". Only ever set when both
     * beat grids were measurable and close, and clamped so the pitch drift stays subtle.
     */
    final float tempoRatio;
    final String summary;

    AutomixPlan(long fireMillis, long overlapMillis, long incomingCueMillis, float matchGain,
                boolean barAligned, boolean bassSwap, float tempoRatio, String summary) {
        this.fireMillis = fireMillis;
        this.overlapMillis = overlapMillis;
        this.incomingCueMillis = incomingCueMillis;
        this.matchGain = matchGain;
        this.barAligned = barAligned;
        this.bassSwap = bassSwap;
        this.tempoRatio = tempoRatio <= 0f || Float.isNaN(tempoRatio) ? 1f : tempoRatio;
        this.summary = summary == null ? "" : summary;
    }

    /** Outgoing gain at {@code progress} in [0,1] of the ramp. */
    float outgoingGain(double progress) {
        return (float) Math.cos(clamp(progress) * Math.PI * .5);
    }

    /**
     * Incoming gain at {@code progress} in [0,1] of the ramp.
     *
     * <p>The loudness match is strongest at the start of the blend, where the incoming track has to
     * hold its own against the outgoing one, and is interpolated back to unity by the end. That way the
     * compensation never survives into the rest of the track as a stuck volume offset, and there is no
     * audible step when the blend finishes.</p>
     */
    float incomingGain(double progress) {
        double p = clamp(progress);
        // The decay is quadratic on purpose. A linear one still falls at full rate where the sine has
        // already flattened out, which makes the last few percent of the ramp move backwards - an
        // audible little dip right at the handover. Squaring kills the tail of the correction first.
        double remaining = 1.0 - p;
        double match = 1.0 + (matchGain - 1.0) * remaining * remaining;
        return (float) (Math.sin(p * Math.PI * .5) * match);
    }

    /**
     * Low shelf gain in dB for the outgoing deck.
     *
     * <p>Pulled down early and held there: the outgoing track hands its low end over well before it
     * hands over its volume, which is what stops two kick drums from stacking. It reads as the outgoing
     * track "thinning out", the same gesture a DJ makes with the low knob.</p>
     */
    float outgoingBassDb(double progress) {
        if (!bassSwap) return 0f;
        double p = clamp(progress) / BASS_CUT_COMPLETE;
        if (p > 1.0) p = 1.0;
        return (float) (AutomixSettings.BASS_CUT_DB * p);
    }

    /**
     * Low shelf gain in dB for the incoming deck.
     *
     * <p>Held back while the outgoing track still owns the low end, then brought up to flat before the
     * blend finishes so the new track is never left sounding thin once it is alone.</p>
     */
    float incomingBassDb(double progress) {
        if (!bassSwap) return 0f;
        double p = clamp(progress);
        if (p <= BASS_ENTRY_START) return AutomixSettings.BASS_ENTRY_CUT_DB;
        if (p >= BASS_ENTRY_COMPLETE) return 0f;
        double share = (p - BASS_ENTRY_START) / (BASS_ENTRY_COMPLETE - BASS_ENTRY_START);
        return (float) (AutomixSettings.BASS_ENTRY_CUT_DB * (1.0 - share));
    }

    /** Playback rate for the outgoing deck at this point in the ramp; 1 when there is no tempo lock. */
    float outgoingRate(double progress) {
        if (tempoRatio == 1f) return 1f;
        double p = clamp(progress) / RATE_RAMP_COMPLETE;
        if (p > 1.0) p = 1.0;
        return (float) (1.0 + (tempoRatio - 1.0) * p);
    }

    private static double clamp(double progress) {
        if (Double.isNaN(progress)) return 0.0;
        return progress < 0.0 ? 0.0 : (progress > 1.0 ? 1.0 : progress);
    }
}
