package com.muoniumplayer.core.ncm.music;

/**
 * Sanity ceiling for a single decoded track.
 *
 * <p>JSyn keeps a whole sample in RAM ({@code FloatSample.allocate} does {@code new float[frames *
 * channels]}), so a 16-bit PCM stream needs roughly twice its byte size as heap. The ceiling here is
 * deliberately generous: it exists to stop a pathological payload (a third-party link pointing at an
 * hours-long upload) from filling the audio cache, not to police long tracks. Anything that still
 * does not fit is handled as an ordinary track failure by the player instead of crashing the game.</p>
 */
final class PlaybackMemoryLimits {

    /** 16-bit PCM turns into 32-bit floats in the player's sample buffer. */
    private static final long HEAP_BYTES_PER_PCM_BYTE = 2L;
    /** Generous share of the heap: a normal-length track, even an hour-long one, must still play. */
    private static final long HEAP_FRACTION = 3L;
    /** ~50 minutes of CD-quality audio; below this nothing is ever rejected. */
    private static final long MIN_PCM_BYTES = 512L * 1024L * 1024L;
    /** ~3 hours of CD-quality audio; beyond this the payload is not music. */
    private static final long MAX_PCM_BYTES = 1900L * 1024L * 1024L;
    private static final long CD_BYTES_PER_SECOND = 44100L * 2L * 2L;

    private PlaybackMemoryLimits() {
    }

    /** Largest decoded PCM payload that can be loaded without risking an out-of-memory failure. */
    static long maxDecodedPcmBytes() {
        long heap = Runtime.getRuntime().maxMemory();
        long budget = heap == Long.MAX_VALUE ? MAX_PCM_BYTES * HEAP_BYTES_PER_PCM_BYTE : heap / HEAP_FRACTION;
        long pcmBytes = budget / HEAP_BYTES_PER_PCM_BYTE;
        return Math.max(MIN_PCM_BYTES, Math.min(MAX_PCM_BYTES, pcmBytes));
    }

    /** User-facing reason used by the playback failure card. */
    static String describeOverLimit(long pcmBytes) {
        long limit = maxDecodedPcmBytes();
        return "音频约 " + minutes(pcmBytes) + " 分钟（上限 " + minutes(limit) + " 分钟），已跳过";
    }

    static long minutes(long pcmBytes) {
        return Math.max(1L, pcmBytes / CD_BYTES_PER_SECOND / 60L);
    }

    static long megabytes(long bytes) {
        return Math.max(1L, bytes / (1024L * 1024L));
    }
}
