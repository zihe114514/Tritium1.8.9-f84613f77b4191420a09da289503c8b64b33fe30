package repackage.processing.sound;

import repackage.com.jsyn.unitgen.FilterLowShelf;

/**
 * Stereo low shelf, used by the automix handover to swap the bass between two decks.
 *
 * <p>Both channels are driven from the same parameters, which is what a DJ mixer's low band does. See
 * {@link FilterLowShelf} for why the blend needs a filter rather than another volume curve.</p>
 */
public class BassSwap extends Effect<FilterLowShelf> {

    public BassSwap() {
        super();
    }

    @Override
    protected FilterLowShelf newInstance() {
        return new FilterLowShelf();
    }

    /** Shelf gain in dB applied to the low band of both channels; 0 is transparent. */
    public void gainDb(float db) {
        this.left.setGainDb(db);
        this.right.setGainDb(db);
    }

    public float gainDb() {
        return this.left.getGainDb();
    }

    /** Corner frequency of the shelf in Hz. */
    public void frequency(float hz) {
        this.left.setFrequency(hz);
        this.right.setFrequency(hz);
    }

    /**
     * Whether this effect is really wired into the given source's circuit right now.
     *
     * <p>{@link Effect#isProcessing()} cannot answer that: {@code AudioSample.pause()} goes through
     * {@code stop()}, which tears the effect out of the circuit but leaves it in the effect's own list
     * of inputs. A deck resumed after a pause would then look attached while its audio actually bypasses
     * the filter - during an automix blend that means the bass swap quietly stops working halfway
     * through.</p>
     */
    public boolean isAttachedTo(SoundObject source) {
        if (source == null) return false;
        JSynCircuit circuit = source.getUnitGenerator();
        return circuit != null && circuit.effect == this;
    }

    /** Forgets a source whose circuit dropped this effect on its own, so it can be attached again. */
    public void forget(SoundObject source) {
        if (source != null) this.inputs.remove(source);
    }

    /**
     * Detaches from every source and releases the filter units.
     *
     * <p>Unlike {@link Effect#stop()} this tolerates a source that has already dropped the effect (so it
     * does not log a spurious error), and it still returns the units to the engine even when the input
     * list has been emptied that way.</p>
     */
    public void release() {
        for (SoundObject source : new java.util.HashSet<>(this.inputs)) {
            if (isAttachedTo(source)) source.removeEffect(this);
        }
        this.inputs.clear();
        Engine.getEngine().remove(this.left);
        Engine.getEngine().remove(this.right);
    }

    /** Jumps to a gain without slewing, for a deck that has not started sounding yet. */
    public void resetTo(float db) {
        this.left.resetTo(db);
        this.right.resetTo(db);
    }
}
