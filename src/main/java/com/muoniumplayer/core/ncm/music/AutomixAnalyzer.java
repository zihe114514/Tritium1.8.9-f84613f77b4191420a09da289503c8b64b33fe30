package com.muoniumplayer.core.ncm.music;

/**
 * Offline analysis used to place an automix handover. Pure DSP: every method takes a mono window that
 * {@link PcmWindowReader} has already read out of the decoded cache file.
 *
 * <p>The windows deliberately do <em>not</em> come from the live JSyn sample. That sample streams from a
 * shared sliding window, so reading an arbitrary position out of a deck that is playing repositions the
 * buffer under the audio thread and kills playback - see {@link PcmWindowReader} for the failure this
 * caused in practice.</p>
 *
 * <p>Three questions are answered:
 * <ul>
 *   <li><b>Beat grid</b> — tempo plus the phase of the first beat and of the first bar, so the
 *       handover can land on a phrase boundary rather than mid-measure. Classic onset-autocorrelation
 *       tempo induction on a time-domain energy envelope (no FFT needed for beat spacing).</li>
 *   <li><b>Intro end</b> — the first sustained rise in energy, i.e. where the incoming track actually
 *       starts, so the overlap is not spent on dead air.</li>
 *   <li><b>Loudness</b> — K-weighted loudness (ITU-R BS.1770-4), so a quiet incoming track can be
 *       lifted to match the outgoing one instead of appearing to drop out. Plain RMS is kept as the
 *       fallback, but it over-rates bass-heavy material: a track with a big kick and no top end reads
 *       louder than it sounds, and matching on it makes the blend lurch.</li>
 * </ul>
 *
 * <p>These are estimates. Every caller treats a {@code null} / non-positive answer as "no structural
 * information" and falls back to a plain equal-power crossfade.</p>
 */
final class AutomixAnalyzer {

    /** Envelope hop in samples (~11.6 ms at 44.1 kHz) — fine enough for beat spacing. */
    private static final int HOP = 512;
    private static final float MIN_BPM = 70f;
    private static final float MAX_BPM = 180f;
    /** Coarser hop for the outro envelope (~23 ms): structure, not beats. */
    private static final int OUTRO_HOP = 1024;
    /** The entrance is nicer just before the kick-in than exactly on it. */
    private static final float INTRO_BACKOFF_MILLIS = 200f;

    private AutomixAnalyzer() {
    }

    /** Estimated tempo plus the beat and bar phase, both in ms from the start of the window. */
    static final class BeatGrid {
        final float bpm;
        final float phaseMillis;
        final float barPhaseMillis;

        BeatGrid(float bpm, float phaseMillis, float barPhaseMillis) {
            this.bpm = bpm;
            this.phaseMillis = phaseMillis;
            this.barPhaseMillis = barPhaseMillis;
        }

        float beatPeriodMillis() {
            return 60_000f / bpm;
        }

        /** One 4-beat bar at this tempo. */
        float barPeriodMillis() {
            return 4f * 60_000f / bpm;
        }
    }

    /**
     * Integrated K-weighted loudness of the window in LUFS, or {@link Float#NaN} when it cannot be
     * measured (empty window, unusable rate, or nothing above the absolute gate).
     *
     * <p>Follows ITU-R BS.1770-4 for a single channel: the two stage K-weighting filter (a ~+4 dB high
     * shelf standing in for the head related transfer function, then a 38 Hz high pass), mean square
     * over 400 ms blocks at 75 % overlap, the −70 LUFS absolute gate and the −10 LU relative gate. The
     * filter coefficients are derived for the window's own sample rate rather than hard-coded for
     * 48 kHz, so a 44.1 kHz track is not measured through a detuned filter.</p>
     *
     * <p>This is what the blend matches on. Two tracks with the same RMS can differ by several LU, and
     * a few LU is exactly the size of step a listener hears as "the volume jumped at the handover".</p>
     */
    static float kWeightedLoudness(float[] mono, int sampleRate) {
        if (mono == null || mono.length == 0 || sampleRate <= 0) return Float.NaN;

        double[] filtered = kWeight(mono, sampleRate);

        int blockFrames = (int) Math.round(sampleRate * .4);
        if (blockFrames <= 0) return Float.NaN;
        int step = Math.max(1, blockFrames / 4);

        if (filtered.length < blockFrames) {
            // Too short for the standard's block structure: report the ungated mean square instead of
            // refusing to answer, because a 1.5 s overlap still needs a level to match on.
            double mean = meanSquare(filtered, 0, filtered.length);
            return mean > 1e-12 ? (float) (-.691 + 10.0 * Math.log10(mean)) : Float.NaN;
        }

        int blocks = (filtered.length - blockFrames) / step + 1;
        double[] blockPower = new double[blocks];
        int kept = 0;
        double gatedSum = 0.0;
        for (int block = 0; block < blocks; block++) {
            double mean = meanSquare(filtered, block * step, blockFrames);
            blockPower[block] = mean;
            // Absolute gate: -70 LUFS.
            if (mean > 1e-12 && -.691 + 10.0 * Math.log10(mean) > -70.0) {
                gatedSum += mean;
                kept++;
            }
        }
        if (kept == 0) return Float.NaN;

        // Relative gate: 10 LU below the level of everything that passed the absolute gate.
        double relativeThreshold = -.691 + 10.0 * Math.log10(gatedSum / kept) - 10.0;
        double finalSum = 0.0;
        int finalCount = 0;
        for (int block = 0; block < blocks; block++) {
            double mean = blockPower[block];
            if (mean <= 1e-12) continue;
            double level = -.691 + 10.0 * Math.log10(mean);
            if (level > -70.0 && level > relativeThreshold) {
                finalSum += mean;
                finalCount++;
            }
        }
        if (finalCount == 0) {
            return (float) (-.691 + 10.0 * Math.log10(gatedSum / kept));
        }
        return (float) (-.691 + 10.0 * Math.log10(finalSum / finalCount));
    }

    private static double meanSquare(double[] values, int offset, int length) {
        if (length <= 0) return 0.0;
        double sum = 0.0;
        int end = Math.min(values.length, offset + length);
        for (int i = offset; i < end; i++) {
            sum += values[i] * values[i];
        }
        int used = end - offset;
        return used <= 0 ? 0.0 : sum / used;
    }

    /**
     * Applies the BS.1770 K-weighting pair. Both stages are biquads whose coefficients come from the
     * standard's reference design (high shelf: 1681.97 Hz, Q 0.7071, +3.9998 dB; high pass: 38.135 Hz,
     * Q 0.5003), mapped to the actual sample rate through the bilinear transform.
     */
    private static double[] kWeight(float[] mono, int sampleRate) {
        double[] output = new double[mono.length];

        // Stage 1: high shelf.
        double shelfGainDb = 3.999843853973347;
        double shelfQ = .7071752369554196;
        double shelfHz = 1681.974450955533;
        double k = Math.tan(Math.PI * shelfHz / sampleRate);
        double vh = Math.pow(10.0, shelfGainDb / 20.0);
        double vb = Math.pow(vh, .4996667741545416);
        double denominator = 1.0 + k / shelfQ + k * k;
        double b0 = (vh + vb * k / shelfQ + k * k) / denominator;
        double b1 = 2.0 * (k * k - vh) / denominator;
        double b2 = (vh - vb * k / shelfQ + k * k) / denominator;
        double a1 = 2.0 * (k * k - 1.0) / denominator;
        double a2 = (1.0 - k / shelfQ + k * k) / denominator;

        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        for (int i = 0; i < mono.length; i++) {
            double x0 = mono[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            output[i] = y0;
        }

        // Stage 2: high pass (RLB weighting).
        double passQ = .5003270373238773;
        double passHz = 38.13547087602444;
        k = Math.tan(Math.PI * passHz / sampleRate);
        denominator = 1.0 + k / passQ + k * k;
        a1 = 2.0 * (k * k - 1.0) / denominator;
        a2 = (1.0 - k / passQ + k * k) / denominator;
        b0 = 1.0;
        b1 = -2.0;
        b2 = 1.0;

        x1 = 0.0;
        x2 = 0.0;
        y1 = 0.0;
        y2 = 0.0;
        for (int i = 0; i < output.length; i++) {
            double x0 = output[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            output[i] = y0;
        }
        return output;
    }

    /** Root mean square of the window; 0 when there is nothing to measure. */
    static float rms(float[] mono) {
        if (mono == null || mono.length == 0) return 0f;
        double squared = 0.0;
        for (float value : mono) {
            squared += (double) value * value;
        }
        return (float) Math.sqrt(squared / mono.length);
    }

    /**
     * Estimates the beat grid of a mono window.
     *
     * @return the grid, or {@code null} when the window is too short or effectively silent.
     */
    static BeatGrid analyzeBeats(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return null;
        int hops = mono.length / HOP;
        if (hops < 16) return null;

        // 1. Onset envelope: half-wave rectified energy flux tracks percussive onsets well enough
        //    for beat spacing, and needs no FFT.
        float[] onset = new float[hops];
        float previousEnergy = 0f;
        double total = 0.0;
        for (int hop = 0; hop < hops; hop++) {
            int base = hop * HOP;
            float energy = 0f;
            for (int index = 0; index < HOP; index++) {
                float value = mono[base + index];
                energy += value * value;
            }
            energy = (float) Math.sqrt(energy / HOP);
            float flux = energy - previousEnergy;
            onset[hop] = flux > 0f ? flux : 0f;
            previousEnergy = energy;
            total += onset[hop];
        }
        if (total < 1e-4) return null;

        // Mean-remove so the autocorrelation measures periodicity instead of the DC level.
        float mean = (float) (total / hops);
        for (int hop = 0; hop < hops; hop++) {
            onset[hop] -= mean;
        }

        float envelopeRate = (float) sampleRate / HOP;
        int minLag = Math.max(1, Math.round(envelopeRate * 60f / MAX_BPM));
        int maxLag = Math.min(hops - 1, Math.round(envelopeRate * 60f / MIN_BPM));
        if (maxLag <= minLag) return null;

        // 2. Autocorrelation across the tempo lag range.
        int bestLag = minLag;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double correlation = 0.0;
            for (int hop = lag; hop < hops; hop++) {
                correlation += (double) onset[hop] * onset[hop - lag];
            }
            // A slight bias toward faster tempi cancels the natural falloff at long lags.
            correlation *= 1.0 + 0.0005 * (maxLag - lag);
            if (correlation > bestScore) {
                bestScore = correlation;
                bestLag = lag;
            }
        }
        float bpm = 60f * envelopeRate / bestLag;

        // 3. Beat phase: the pulse-train offset with the highest onset sum.
        int bestOffset = 0;
        float bestSum = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < bestLag; offset++) {
            float sum = 0f;
            for (int hop = offset; hop < hops; hop += bestLag) {
                sum += onset[hop];
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestOffset = offset;
            }
        }
        float millisPerHop = HOP * 1000f / sampleRate;
        float phaseMillis = bestOffset * millisPerHop;

        // 3b. Bar phase: re-fit at four times the period. Only the four offsets that are whole beats
        //     away from the beat phase are tested, so the answer is a real downbeat (beat 1 of 4) and
        //     not an arbitrary point inside a beat. Stepping by a fraction of a beat here — as is easy
        //     to do by accident — returns offsets that are not on the grid at all.
        int barLag = bestLag * 4;
        if (barLag >= hops) return new BeatGrid(bpm, phaseMillis, phaseMillis);

        float bestBarSum = Float.NEGATIVE_INFINITY;
        int bestBarOffset = bestOffset;
        for (int beat = 0; beat < 4; beat++) {
            int offset = bestOffset + beat * bestLag;
            if (offset >= hops) break;
            float sum = 0f;
            for (int hop = offset; hop < hops; hop += barLag) {
                sum += onset[hop];
            }
            if (sum > bestBarSum) {
                bestBarSum = sum;
                bestBarOffset = offset;
            }
        }
        return new BeatGrid(bpm, phaseMillis, bestBarOffset * millisPerHop);
    }

    /**
     * Finds where an intro ends: the first hop that reaches a meaningful share of the window peak and
     * then stays there for about a second. A single drum hit is not an entrance, so the sustain check
     * matters more than the step itself.
     *
     * @return offset in ms from the start of the window, or 0 when no clear entrance was found.
     */
    static float detectIntroEnd(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return 0f;
        int hops = mono.length / HOP;
        if (hops < 16) return 0f;

        float[] levels = new float[hops];
        float peak = 0f;
        for (int hop = 0; hop < hops; hop++) {
            int base = hop * HOP;
            double squared = 0.0;
            for (int index = 0; index < HOP; index++) {
                float value = mono[base + index];
                squared += (double) value * value;
            }
            levels[hop] = (float) Math.sqrt(squared / HOP);
            if (levels[hop] > peak) peak = levels[hop];
        }
        if (peak < .001f) return 0f;

        float millisPerHop = HOP * 1000f / sampleRate;
        int sustainHops = Math.max(4, Math.round(1000f / millisPerHop));
        float entranceFloor = peak * .40f;
        float sustainFloor = peak * .30f;

        for (int hop = 0; hop < hops; hop++) {
            if (levels[hop] < entranceFloor) continue;
            int end = Math.min(hops, hop + sustainHops);
            boolean sustained = true;
            for (int probe = hop; probe < end; probe++) {
                if (levels[probe] < sustainFloor) {
                    sustained = false;
                    break;
                }
            }
            // Back off a hair so the entry lands on the last quiet beat rather than clipping the
            // downbeat that starts the song.
            if (sustained) return Math.max(0f, hop * millisPerHop - INTRO_BACKOFF_MILLIS);
        }

        // Nothing sustained (a quiet or spoken opening): do not invent a landing point.
        return 0f;
    }

    /**
     * Finds where the outgoing track's structural outro begins: the end of the last sustained
     * high-energy section, followed by the drop into the calmer ending. That is where a DJ mixes out,
     * and it lands on a musical boundary instead of on the literal last seconds of the file.
     *
     * <p>Falls back to the last sustained quiet gap (fade-out endings have no drop to find), and then
     * to the quietest hop near the end. Returns {@code -1} only when the window carries no level at
     * all, in which case the caller keeps its positional default.</p>
     *
     * @return offset in ms from the start of the window, or {@code -1} when nothing could be measured
     */
    static float detectOutroLanding(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return -1f;
        int hop = OUTRO_HOP;
        int hops = mono.length / hop;
        if (hops < 8) return -1f;

        float[] levels = new float[hops];
        double sum = 0.0;
        float peak = 0f;
        for (int index = 0; index < hops; index++) {
            int base = index * hop;
            double squared = 0.0;
            for (int offset = 0; offset < hop; offset++) {
                float value = mono[base + offset];
                squared += (double) value * value;
            }
            levels[index] = (float) Math.sqrt(squared / hop);
            sum += levels[index];
            if (levels[index] > peak) peak = levels[index];
        }
        float mean = (float) (sum / hops);
        if (mean < 1e-4f) return -1f;

        float millisPerHop = hop * 1000f / sampleRate;
        float highFloor = peak * .70f;
        float dropFloor = mean * .55f;
        int neededHigh = Math.max(2, Math.round(2000f / millisPerHop));

        // The END of the last sustained loud section - the final chorus or drop.
        int lastHighEnd = -1;
        int index = 0;
        while (index < hops) {
            if (levels[index] < highFloor) {
                index++;
                continue;
            }
            int start = index;
            while (index < hops && levels[index] >= highFloor) index++;
            if (index - start >= neededHigh) lastHighEnd = index;
        }
        if (lastHighEnd >= 0 && lastHighEnd < hops - 1) {
            int lookahead = Math.max(2, Math.round(300f / millisPerHop));
            for (int probe = lastHighEnd; probe < hops - lookahead; probe++) {
                boolean below = true;
                for (int inner = probe; inner < probe + lookahead; inner++) {
                    if (levels[inner] >= dropFloor) {
                        below = false;
                        break;
                    }
                }
                if (below) return probe * millisPerHop;
            }
            // The loud section runs all the way out: mix right after it instead.
            return lastHighEnd * millisPerHop;
        }

        // Fade-out ending: the last sustained quiet gap is the only breathing point there is.
        int needed = Math.max(1, Math.round(600f / millisPerHop));
        for (int probe = hops - needed; probe >= 0; probe--) {
            boolean quiet = true;
            for (int inner = probe; inner < probe + needed; inner++) {
                if (levels[inner] > dropFloor) {
                    quiet = false;
                    break;
                }
            }
            if (quiet) return probe * millisPerHop;
        }

        // Dense tail: settle for the quietest moment in the last 15 seconds.
        int scanFrom = Math.max(0, hops - Math.round(15_000f / millisPerHop));
        int quietest = scanFrom;
        for (int probe = scanFrom; probe < hops; probe++) {
            if (levels[probe] < levels[quietest]) quietest = probe;
        }
        return quietest * millisPerHop;
    }

    /**
     * Folds a tempo into the same octave as the reference, so a half/double-time detection does not
     * look like a mismatch.
     */
    static float octaveNormalise(float bpm, float reference) {
        if (bpm <= 1f || reference <= 1f) return bpm;
        float best = bpm;
        float bestDistance = Math.abs(bpm - reference);
        float[] candidates = {bpm * .5f, bpm * 2f, bpm * (2f / 3f), bpm * 1.5f};
        for (float candidate : candidates) {
            if (candidate < MIN_BPM || candidate > MAX_BPM) continue;
            float distance = Math.abs(candidate - reference);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
