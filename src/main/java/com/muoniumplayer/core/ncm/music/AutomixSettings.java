package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * Persisted automix (seamless track handover) preferences.
 *
 * <p>Automix keeps the outgoing track audible while the next one is already decoded and running on a
 * second deck, so a queue advance becomes an overlap instead of the old close-player/sleep(250ms)/
 * download/decode gap. Everything here is opt-out: with automix disabled the playback path is
 * byte-for-byte the original single-deck sequence.</p>
 */
public final class AutomixSettings {

    /** Shortest overlap that still reads as a blend rather than a cut. */
    public static final float MIN_OVERLAP_SECONDS = 1.5f;
    /** Longest overlap; beyond this the two tracks fight each other for too long. */
    public static final float MAX_OVERLAP_SECONDS = 10.0f;
    public static final float DEFAULT_OVERLAP_SECONDS = 4.0f;

    /**
     * Corner frequency of the automix low shelf. 200 Hz is where a kick drum's body and a bass line
     * live: cutting below it takes the mud out of an overlap without thinning the vocals.
     */
    public static final float BASS_SHELF_HZ = 200f;
    /** How far the outgoing deck's bass is pulled down at the point where the incoming one takes over. */
    public static final float BASS_CUT_DB = -18f;
    /** How far the incoming deck's bass starts suppressed before it is allowed into the mix. */
    public static final float BASS_ENTRY_CUT_DB = -12f;

    /**
     * How early the next track starts being resolved/downloaded/decoded, on top of the overlap.
     * Decoding a normal track costs a few seconds; a fragmented-MP4 one can cost more, so the arm
     * window is generous. An arm that is not ready in time simply degrades to the ordinary switch.
     */
    static final long ARM_LEAD_MILLIS = 30_000L;

    /**
     * A second deck doubles the resident PCM, so automix refuses to arm oversized tracks. 120 MB of
     * 16-bit CD audio is about 11 minutes — long enough for ordinary music, short enough that two
     * live decks cannot exhaust the heap the way an hour-long upload would.
     */
    private static final long ABSOLUTE_DECK_LIMIT_BYTES = 120L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static boolean loaded;
    private static boolean enabled = true;
    private static boolean beatAlign = true;
    private static boolean bassSwap = true;
    private static boolean tempoLock = true;
    private static float overlapSeconds = DEFAULT_OVERLAP_SECONDS;

    private AutomixSettings() {
    }

    /** Largest decoded file automix may hold on the idle deck. */
    static long maxDeckBytes() {
        return Math.min(ABSOLUTE_DECK_LIMIT_BYTES, PlaybackMemoryLimits.maxDecodedPcmBytes() / 2L);
    }

    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (enabled == value) return;
            enabled = value;
            saveLocked();
        }
    }

    /** Whether the handover may be nudged onto a detected bar boundary. */
    public static boolean isBeatAlignEnabled() {
        load();
        synchronized (LOCK) {
            return beatAlign;
        }
    }

    public static void setBeatAlignEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (beatAlign == value) return;
            beatAlign = value;
            saveLocked();
        }
    }

    /**
     * Whether the blend swaps the low band between the two decks instead of only crossfading volume.
     *
     * <p>Two tracks overlapping put two kick drums and two bass lines into the same octave, which sums
     * into a boom that no volume curve can fix - it is the single most obvious "this is a crossfade"
     * artefact. Pulling the low shelf out of the outgoing deck and holding the incoming one's bass back
     * until it owns the mix is what club DJs do, and it is what makes the handover sound deliberate.</p>
     */
    public static boolean isBassSwapEnabled() {
        load();
        synchronized (LOCK) {
            return bassSwap;
        }
    }

    public static void setBassSwapEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (bassSwap == value) return;
            bassSwap = value;
            saveLocked();
        }
    }

    /**
     * Whether the outgoing deck may be bent onto the incoming track's tempo for the length of the blend.
     *
     * <p>Without it, two tracks that are 4 % apart drift by a third of a beat over a four second
     * overlap, so a handover that started beat matched ends up flamming. The correction is capped and
     * only ever applied to the deck that is disappearing.</p>
     */
    public static boolean isTempoLockEnabled() {
        load();
        synchronized (LOCK) {
            return tempoLock;
        }
    }

    public static void setTempoLockEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (tempoLock == value) return;
            tempoLock = value;
            saveLocked();
        }
    }

    public static float getOverlapSeconds() {
        load();
        synchronized (LOCK) {
            return overlapSeconds;
        }
    }

    public static long getOverlapMillis() {
        return (long) (getOverlapSeconds() * 1000f);
    }

    public static void setOverlapSeconds(float value) {
        load();
        synchronized (LOCK) {
            float clamped = clamp(value);
            if (Math.abs(clamped - overlapSeconds) < .01f) return;
            overlapSeconds = clamped;
            saveLocked();
        }
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return DEFAULT_OVERLAP_SECONDS;
        return Math.max(MIN_OVERLAP_SECONDS, Math.min(MAX_OVERLAP_SECONDS, value));
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.AUTOMIX;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                if (root.has("enabled") && !root.get("enabled").isJsonNull()) {
                    enabled = root.get("enabled").getAsBoolean();
                }
                if (root.has("beatAlign") && !root.get("beatAlign").isJsonNull()) {
                    beatAlign = root.get("beatAlign").getAsBoolean();
                }
                if (root.has("bassSwap") && !root.get("bassSwap").isJsonNull()) {
                    bassSwap = root.get("bassSwap").getAsBoolean();
                }
                if (root.has("tempoLock") && !root.get("tempoLock").isJsonNull()) {
                    tempoLock = root.get("tempoLock").getAsBoolean();
                }
                if (root.has("overlapSeconds") && !root.get("overlapSeconds").isJsonNull()) {
                    overlapSeconds = clamp(root.get("overlapSeconds").getAsFloat());
                }
            } catch (Throwable ignored) {
                // A damaged automix config must never stop playback from starting.
            }
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.AUTOMIX.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("beatAlign", beatAlign);
            root.addProperty("bassSwap", bassSwap);
            root.addProperty("tempoLock", tempoLock);
            root.addProperty("overlapSeconds", overlapSeconds);
            JsonConfigStorage.writeObject(ConfigPaths.AUTOMIX, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}
