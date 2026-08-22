package repackage.com.jsyn.unitgen;

/**
 * A second order low shelf filter (Robert Bristow-Johnson's audio EQ cookbook form) with a smoothed,
 * thread safe gain parameter.
 *
 * <p>It exists for the automix bass swap: when two tracks overlap, the one thing that instantly gives
 * the blend away is both of them putting their kick and bass into the same 20-250 Hz window, which sums
 * to a muddy boom no amount of volume fading can fix. A DJ solves this by pulling the low band out of
 * the outgoing track and only letting the incoming one's bass in once it owns the mix. That needs a
 * real filter, not a volume curve, so this is one.</p>
 *
 * <h3>Two deliberate properties</h3>
 * <ul>
 *   <li><b>Bit-exact bypass.</b> At (or very near) 0 dB the filter copies its input and clears its
 *       state, so leaving one permanently attached to every deck cannot colour ordinary playback.</li>
 *   <li><b>Smoothed gain.</b> {@link #setGainDb(float)} is called from the player's 10 ms supervisor
 *       tick; stepping biquad coefficients at that rate would zipper. The gain follows the target with
 *       a one pole slew and the coefficients are only recomputed when it has actually moved.</li>
 * </ul>
 */
public class FilterLowShelf extends UnitFilter {

    /** Above this the shelf would start eating the low mids as well. */
    private static final double MAX_FREQUENCY = 1_000.0;
    private static final double MIN_FREQUENCY = 30.0;
    /** Gain changes below this are inaudible, and treated as "unchanged" to skip a coefficient update. */
    private static final double GAIN_EPSILON_DB = .02;
    /** Slew time constant. Long enough to be inaudible, short enough to track a 10 ms driver. */
    private static final double SLEW_SECONDS = .025;

    private volatile double targetGainDb;
    private volatile double frequency = 200.0;

    private double currentGainDb;
    private double computedGainDb = Double.NaN;
    private double computedFrequency = Double.NaN;

    /**
     * Frame rate, resolved on first use. {@code getFrameRate()} reads through the synthesis engine and
     * throws while the unit is detached, which is exactly the state a unit test - or a filter that has
     * been removed from the graph mid-block - is in.
     */
    private int cachedRate;

    private double b0 = 1.0, b1, b2, a1, a2;
    private double x1, x2, y1, y2;

    /**
     * @param db shelf gain in dB for the low band; negative cuts the bass. 0 bypasses the filter.
     */
    public void setGainDb(float db) {
        if (Float.isNaN(db) || Float.isInfinite(db)) return;
        this.targetGainDb = Math.max(-40.0, Math.min(12.0, db));
    }

    public float getGainDb() {
        return (float) this.targetGainDb;
    }

    /** Corner frequency of the shelf in Hz. */
    public void setFrequency(float hz) {
        if (Float.isNaN(hz) || Float.isInfinite(hz)) return;
        this.frequency = Math.max(MIN_FREQUENCY, Math.min(MAX_FREQUENCY, hz));
    }

    /** Drops the filter straight to unity without a slew, for a deck that is about to start. */
    public void resetTo(float db) {
        setGainDb(db);
        this.currentGainDb = this.targetGainDb;
        this.computedGainDb = Double.NaN;
        clearState();
    }

    private void clearState() {
        this.x1 = 0.0;
        this.x2 = 0.0;
        this.y1 = 0.0;
        this.y2 = 0.0;
    }

    @Override
    public void generate(int start, int limit) {
        double[] inputs = input.getValues();
        double[] outputs = output.getValues();

        int frames = limit - start;
        if (frames <= 0) return;

        double target = this.targetGainDb;
        int rate = this.cachedRate;
        if (rate <= 0) {
            try {
                rate = getFrameRate();
            } catch (Throwable ignored) {
                rate = 0;
            }
            if (rate <= 0) rate = 44100;
            this.cachedRate = rate;
        }

        // One pole slew towards the target, evaluated once per block: a block is a fraction of a
        // millisecond, so per-sample interpolation would only cost cycles.
        double coefficient = 1.0 - Math.exp(-frames / (SLEW_SECONDS * rate));
        this.currentGainDb += (target - this.currentGainDb) * coefficient;
        if (Math.abs(target - this.currentGainDb) < GAIN_EPSILON_DB) {
            this.currentGainDb = target;
        }

        if (Math.abs(this.currentGainDb) < GAIN_EPSILON_DB && Math.abs(target) < GAIN_EPSILON_DB) {
            // Flat: pass the signal through untouched rather than through a unity biquad, so an
            // always-attached filter is provably transparent for ordinary playback.
            clearState();
            for (int i = start; i < limit; i++) {
                outputs[i] = inputs[i];
            }
            return;
        }

        double frequencyNow = this.frequency;
        if (Double.isNaN(this.computedGainDb)
                || Math.abs(this.currentGainDb - this.computedGainDb) >= GAIN_EPSILON_DB
                || frequencyNow != this.computedFrequency) {
            updateCoefficients(this.currentGainDb, frequencyNow, rate);
        }

        for (int i = start; i < limit; i++) {
            double x0 = inputs[i];
            double y0 = this.b0 * x0 + this.b1 * this.x1 + this.b2 * this.x2
                    - this.a1 * this.y1 - this.a2 * this.y2;
            if (y0 != y0 || Double.isInfinite(y0)) {
                // A denormal storm or a NaN arriving from a broken decoder must not lock the filter
                // into producing silence (or worse) for the rest of the track.
                clearState();
                y0 = x0;
            }
            this.x2 = this.x1;
            this.x1 = x0;
            this.y2 = this.y1;
            this.y1 = y0;
            outputs[i] = y0;
        }
    }

    private void updateCoefficients(double gainDb, double frequencyHz, int rate) {
        this.computedGainDb = gainDb;
        this.computedFrequency = frequencyHz;

        double amplitude = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * Math.max(MIN_FREQUENCY, Math.min(rate * .45, frequencyHz)) / rate;
        double cos = Math.cos(w0);
        // Shelf slope S = 1, i.e. the steepest slope that still has no gain ripple.
        double alpha = Math.sin(w0) * .5 * Math.sqrt(2.0);
        double sqrtA = Math.sqrt(amplitude);
        double shared = 2.0 * sqrtA * alpha;

        double a0 = (amplitude + 1.0) + (amplitude - 1.0) * cos + shared;
        if (a0 == 0.0 || Double.isNaN(a0)) {
            this.b0 = 1.0;
            this.b1 = 0.0;
            this.b2 = 0.0;
            this.a1 = 0.0;
            this.a2 = 0.0;
            return;
        }

        this.b0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cos + shared) / a0;
        this.b1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cos) / a0;
        this.b2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cos - shared) / a0;
        this.a1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cos) / a0;
        this.a2 = ((amplitude + 1.0) + (amplitude - 1.0) * cos - shared) / a0;
    }
}
