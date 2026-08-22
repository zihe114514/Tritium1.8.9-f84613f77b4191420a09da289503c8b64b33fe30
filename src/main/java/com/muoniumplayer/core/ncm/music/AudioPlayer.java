package com.muoniumplayer.core.ncm.music;

import lombok.Getter;
import lombok.SneakyThrows;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import repackage.processing.sound.BassSwap;
import repackage.processing.sound.SoundFile;

import java.io.File;

/**
 * 播放视频里面的音频
 */
public class AudioPlayer {
    public SoundFile player;
    public Runnable afterPlayed;

    @Getter
    public float volume = 0.25f;

    /**
     * Crossfade multiplier applied on top of {@link #volume}. It exists so an automix handover can ramp
     * one deck down while another ramps up without touching the persisted player volume, which both
     * decks keep sharing.
     */
    private volatile float fadeGain = 1.0f;

    /**
     * Low shelf sitting in this deck's own signal chain, used by the automix bass swap. Only attached
     * when automix is actually going to use it, so a player with automix off keeps exactly the original
     * graph. At 0 dB the filter is a bit-exact pass-through, so it can stay attached for a whole track.
     */
    private BassSwap bassSwap;
    private volatile float bassGainDb = 0.0f;
    private volatile float playbackRate = 1.0f;

    /**
     * The decoded cache file this deck was built from, kept so offline analysis can open it again with
     * its own handle. Reading the live JSyn sample instead is not an option: it streams from a shared
     * sliding window, and moving that window under the audio thread breaks playback outright.
     */
    private volatile File sourceFile;

    /**
     * Some downloaded files can be opened by the decoder but contain no usable
     * audio frames. AudioSample.position() performs a modulo by frames(), so an
     * empty sample must never reach the playback or rendering paths.
     */
    private boolean closed;

    public AudioPlayer(File file) {
        finished = false;
        this.player = loadUsableSoundFile(file);
        this.sourceFile = file;
        this.setListeners();
        this.closed = false;
    }

    public void setAudio(File file) {
        // Decode and validate before closing the currently playing song. A
        // corrupt/empty replacement must not destroy an otherwise usable player.
        SoundFile next = loadUsableSoundFile(file);
        next.setOnFinished(() -> finished = true);

        releaseBassSwap();
        SoundFile previous = this.player;
        this.player = next;
        finished = false;
        this.closed = false;
        this.bassGainDb = 0.0f;
        this.playbackRate = 1.0f;
        this.sourceFile = file;
        dispose(previous);
    }

    public void setListeners() {
        if (player != null)
            player.setOnFinished(() -> finished = true);
    }

    public void play() {
        if (!isUsable())
            return;

        attachBassSwap();

        finished = false;
        try {
            // Amplitude first: playback starts at the configured level instead of at the engine's
            // default of full volume for the first few rendered frames.
            this.player.amp(effectiveAmp());
            this.player.play();
            this.player.amp(effectiveAmp());
        } catch (ChannelMismatchException mismatch) {
            // Preserve the existing retry path in CloudMusic for channel changes.
            throw mismatch;
        } catch (RuntimeException ignored) {
            // A decoder can become invalid between validation and playback.
        }
    }

    @SneakyThrows
    public void setPlaybackTime(float millis) {
        if (!isUsable())
            return;

        float total = getTotalTimeMillis();
        if (total <= 0.0f)
            return;

        float safeMillis = Math.max(0.0f, Math.min(total, millis));
        try {
            this.player.jump(safeMillis / 1000F);
            this.player.amp(effectiveAmp());
        } catch (RuntimeException ignored) {
            // Seeking is best-effort. A malformed stream must not crash the UI.
        }
    }

    /**
     * Starts playback from an explicit offset. Used by the automix handover so the incoming deck can
     * enter after a dead intro instead of always starting at zero.
     *
     * <p>{@code cue()} stops the reader first, so the following {@code play()} re-uses the same JSyn
     * voice rather than allocating a second concurrent one.</p>
     */
    public void playFrom(float millis) {
        if (!isUsable())
            return;

        attachBassSwap();

        finished = false;
        float total = getTotalTimeMillis();
        float safeMillis = total <= 0.0f
                ? 0.0f
                : Math.max(0.0f, Math.min(Math.max(0.0f, total - 50.0f), millis));
        try {
            this.player.amp(effectiveAmp());
            if (safeMillis > 0.0f) this.player.cue(safeMillis / 1000F);
            this.player.play();
            this.player.amp(effectiveAmp());
        } catch (ChannelMismatchException mismatch) {
            throw mismatch;
        } catch (RuntimeException ignored) {
            // A decoder can become invalid between validation and playback.
        }
    }

    /**
     * Sets the crossfade multiplier. {@code 0} is silent, {@code 1} is the configured player volume;
     * values above 1 are allowed so a quiet incoming track can be loudness-matched, and the product is
     * clamped to the engine's valid amplitude range.
     */
    public void setFadeGain(float gain) {
        this.fadeGain = Float.isNaN(gain) || Float.isInfinite(gain) ? 0.0f : Math.max(0.0f, gain);
        applyAmp();
    }

    public float getFadeGain() {
        return this.fadeGain;
    }

    /**
     * Attaches the automix low shelf if it is not already in the chain.
     *
     * <p>Called just before the deck starts sounding, so the graph is never rewired while audio is
     * running through it. The effect is only created when automix is actually allowed to use it: with
     * automix (or the bass swap) switched off, the deck keeps the original, effect-free chain.</p>
     */
    private void attachBassSwap() {
        if (!AutomixSettings.isEnabled() || !AutomixSettings.isBassSwapEnabled()) return;
        if (!isUsable()) return;
        try {
            if (this.bassSwap == null) {
                this.bassSwap = new BassSwap();
                this.bassSwap.frequency(AutomixSettings.BASS_SHELF_HZ);
            }
            this.bassSwap.resetTo(this.bassGainDb);
            // Not isProcessing(): pause() rips the effect out of the circuit without telling the
            // effect, so a resumed deck has to be re-wired or it would play unfiltered.
            if (!this.bassSwap.isAttachedTo(this.player)) {
                this.bassSwap.forget(this.player);
                this.bassSwap.process(this.player);
            }
        } catch (Throwable ignored) {
            // An engine that refuses the extra units must not stop the track from playing.
            this.bassSwap = null;
        }
    }

    /**
     * Sets the low shelf gain in dB for this deck. Negative values pull the bass out, which is how the
     * handover keeps two overlapping tracks from stacking their kick drums into one boom.
     *
     * <p>A no-op when no shelf is attached (automix or the bass swap disabled), so callers do not have
     * to know which decks are filtered.</p>
     */
    public void setBassGainDb(float db) {
        float safe = Float.isNaN(db) || Float.isInfinite(db) ? 0.0f : db;
        this.bassGainDb = safe;
        BassSwap swap = this.bassSwap;
        if (swap == null) return;
        try {
            swap.gainDb(safe);
        } catch (RuntimeException ignored) {
        }
    }

    /** The decoded file behind this deck, or {@code null} when it is not known. */
    public File getSourceFile() {
        return this.sourceFile;
    }

    public float getBassGainDb() {
        return this.bassGainDb;
    }

    /**
     * Relative playback rate, 1.0 being the file's own tempo.
     *
     * <p>Used only on the deck that is fading <em>out</em> of a blend: nudging it onto the incoming
     * track's tempo keeps the two beat grids locked for the whole overlap instead of letting them drift
     * apart by a fraction of a beat. JSyn's rate control also shifts pitch, which is exactly why the
     * outgoing side is the one that gets bent - by then it is a few dB down and on its way out, while
     * the track the listener is about to keep listening to stays at its own pitch.</p>
     */
    public void setPlaybackRate(float ratio) {
        if (Float.isNaN(ratio) || Float.isInfinite(ratio)) return;
        float safe = Math.max(.5f, Math.min(2.0f, ratio));
        // The fade driver ticks every 10 ms and holds the rate flat for most of the blend; re-setting
        // the port with an unchanged value every tick would be pure overhead.
        if (Math.abs(safe - this.playbackRate) < .0005f) return;
        this.playbackRate = safe;
        if (!isUsable()) return;
        try {
            this.player.rate(safe);
        } catch (RuntimeException ignored) {
        }
    }

    public float getPlaybackRate() {
        return this.playbackRate;
    }

    /** Returns the deck to flat response and natural tempo. */
    public void resetAutomixProcessing() {
        setBassGainDb(0.0f);
        if (Math.abs(this.playbackRate - 1.0f) > .0005f) setPlaybackRate(1.0f);
    }

    private void releaseBassSwap() {
        BassSwap swap = this.bassSwap;
        this.bassSwap = null;
        if (swap == null) return;
        try {
            swap.release();
        } catch (Throwable ignored) {
        }
    }

    /** Effective amplitude handed to the engine: persisted volume scaled by the crossfade gain. */
    private float effectiveAmp() {
        float amp = this.volume * this.fadeGain;
        if (Float.isNaN(amp) || Float.isInfinite(amp)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, amp));
    }

    private void applyAmp() {
        if (!isUsable()) return;
        try {
            this.player.amp(effectiveAmp());
        } catch (RuntimeException ignored) {
            // Re-applied on the next successful playback operation.
        }
    }

    /**
     * Idempotent on purpose. A deck can now be released from several places (the play loop, a cancelled
     * switch, the end of a crossfade), and disposing a already cleaned-up sample would hand JSyn a null
     * buffer to queue.
     */
    @SneakyThrows
    public synchronized void close() {
        if (closed) return;
        closed = true;
        releaseBassSwap();
        dispose(this.player);
    }

    @Getter
    private boolean finished;

    public void setAfterPlayed(Runnable runnable) {
        this.afterPlayed = runnable;
        if (this.player != null)
            this.player.setOnFinished(() -> {
                finished = true;
                if (runnable != null)
                    runnable.run();
            });
    }

    public float getTotalTimeSeconds() {
        return (int) (getTotalTimeMillis() / 1000.0f);
    }

    public float getCurrentTimeSeconds() {
        return (int) (getCurrentTimeMillis() / 1000);
    }

    public float getTotalTimeMillis() {
        // 直接由原始浮点秒换算毫秒（round 到最近毫秒），保留小数时长。
        if (!isUsable())
            return 0.0f;

        try {
            float duration = this.player.duration();
            if (Float.isNaN(duration) || Float.isInfinite(duration) || duration <= 0.0f)
                return 0.0f;
            return Math.max(0.0f, Math.round(duration * 1000.0f));
        } catch (RuntimeException ignored) {
            return 0.0f;
        }
    }

    public float getCurrentTimeMillis() {
        if (!isUsable())
            return 0.0f;

        try {
            float position = this.player.position() * 1000.0f;
            if (Float.isNaN(position) || Float.isInfinite(position))
                return 0.0f;

            float total = getTotalTimeMillis();
            if (total <= 0.0f)
                return 0.0f;
            return Math.max(0.0f, Math.min(total, position));
        } catch (RuntimeException ignored) {
            // AudioSample.position() uses `% frames()` and can throw when a
            // decoder produced an empty sample. Report a safe position.
            return 0.0f;
        }
    }

    public boolean isPausing() {
        return !isUsable() || !this.player.isPlaying();
    }

    public void setVolume(float volume) {
        this.volume = volume;
        applyAmp();
    }

    public void pause() {
        if (isUsable()) {
            try {
                this.player.pause();
            } catch (RuntimeException ignored) {
                // Ignore malformed decoder state.
            }
        }
    }

    public void unpause() {
        this.play();
    }

    /**
     * Returns whether the decoder exposed a non-empty, measurable sample.
     */
    public boolean isUsable() {
        if (closed || player == null)
            return false;
        return isUsable(player);
    }

    private static SoundFile loadUsableSoundFile(File file) {
        if (file == null)
            throw new IllegalArgumentException("Audio file is null");

        // Decoded PCM caches from an older build (or an unusually long stream) can exceed what the
        // player may hold in RAM. Reject them up front and treat a failed allocation as a track
        // failure rather than letting an OutOfMemoryError escape into the game loop.
        long pcmLimit = PlaybackMemoryLimits.maxDecodedPcmBytes();
        if (file.isFile() && file.length() > pcmLimit) {
            throw new IllegalStateException(PlaybackMemoryLimits.describeOverLimit(file.length()));
        }

        SoundFile candidate;
        try {
            candidate = new SoundFile(file.getAbsolutePath());
        } catch (OutOfMemoryError error) {
            throw new IllegalStateException("音频过长，内存不足以载入：" + file.getName());
        }
        if (isUsable(candidate))
            return candidate;

        dispose(candidate);
        throw new IllegalStateException("Audio file contains no usable frames: " + file.getName());
    }

    private static boolean isUsable(SoundFile soundFile) {
        if (soundFile == null)
            return false;

        try {
            int frames = soundFile.frames();
            int sampleRate = soundFile.sampleRate();
            float duration = soundFile.duration();
            return frames > 0 && sampleRate > 0
                    && !Float.isNaN(duration)
                    && !Float.isInfinite(duration)
                    && duration > 0.0f;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void dispose(SoundFile soundFile) {
        if (soundFile == null)
            return;

        try {
            if (isUsable(soundFile))
                soundFile.jump(0.0f);
        } catch (RuntimeException ignored) {
        }
        try {
            soundFile.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            soundFile.cleanUp();
        } catch (RuntimeException ignored) {
        }
    }
}
