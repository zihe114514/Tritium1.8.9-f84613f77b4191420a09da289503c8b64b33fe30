package com.muoniumplayer.core.ncm.music;

import java.util.Locale;

/**
 * Builds the {@link AutomixPlan} for a handover between two already decoded decks.
 *
 * <p>The structural choices follow the DJ logic the reference implementation documents: mix out of the
 * outgoing track once its vocals are done and its arrangement thins out, mix into the incoming track
 * where its intro actually ends, land the handover on a bar boundary, and match loudness so neither
 * side appears to drop.</p>
 *
 * <p>On top of that the plan carries the two things that actually separate a DJ blend from a crossfade.
 * <b>The low band is swapped, not summed</b>: two tracks overlapping put two kick drums and two bass
 * lines into the same octave, and that sums into a boom no volume curve can fix, so the outgoing deck's
 * low shelf is pulled out early and the incoming one's is held back until it owns the mix. <b>The
 * outgoing tempo is bent onto the incoming one</b> when both grids were measurable and close, because
 * two tracks 4 % apart drift by a third of a beat over a four second overlap - a handover that starts
 * beat matched and ends flamming. Only the deck that is leaving gets bent: JSyn's rate control also
 * shifts pitch, and a couple of percent on a track that is already several dB down and on its way out
 * is inaudible, whereas doing it to the arriving track would detune the rest of the song.</p>
 *
 * <p><b>Analysis is never a requirement</b>: when a window cannot be measured the plan degrades to a
 * plain equal-power crossfade instead of falling back to a hard cut, because the whole point is that
 * the queue never falls silent between two tracks.</p>
 */
final class AutomixPlanner {

    /** Window used for tempo induction; a few seconds are needed to lock onto a beat period. */
    private static final long BEAT_WINDOW_MILLIS = 12_000L;
    /**
     * How much of an incoming intro may be skipped. Deliberately conservative: the point is to avoid
     * blending into dead air, not to edit the song, so a long deliberate intro is played in full.
     */
    private static final long MAX_INTRO_SKIP_MILLIS = 12_000L;
    /** An intro is never allowed to eat more than this share of a short track. */
    private static final double MAX_INTRO_SKIP_FRACTION = .12;
    /** Beat alignment is only claimed inside this tempo band, mirroring the reference's vibe check. */
    private static final float MIN_TEMPO_RATIO = .92f;
    private static final float MAX_TEMPO_RATIO = 1.08f;
    /** Bounds on the tempo bend applied to the outgoing deck; beyond this the pitch drift is audible. */
    private static final float MIN_RATE = .94f;
    private static final float MAX_RATE = 1.06f;
    /** A bend this small is inaudible and not worth touching the rate port for. */
    private static final float MIN_USEFUL_RATE_DELTA = .004f;
    /** Loudness compensation stays gentle; a big correction would just sound like a volume jump. */
    private static final float MIN_GAIN = .65f;
    private static final float MAX_GAIN = 1.60f;
    /** Below this the outgoing track is too short for an overlap to be anything but a cut. */
    private static final long MIN_OUTGOING_MILLIS = 12_000L;
    private static final long MIN_INCOMING_MILLIS = 8_000L;
    /**
     * How much of the outgoing tail may be traded for an earlier, more musical mix-out. Mixing out at
     * the structural outro is what makes a transition sound intentional, but cutting the end off a song
     * is exactly what users notice and complain about, so the trim is bounded both absolutely and as a
     * share of the track - and a mix-out estimate that falls outside those bounds is discarded rather
     * than clamped onto them.
     */
    private static final long MAX_TRIM_MILLIS = 12_000L;
    private static final double MIN_TRIM_POSITION = .60;
    /** Tail scanned for the structural outro when no lyric boundary is available. */
    private static final long OUTRO_SCAN_MILLIS = 45_000L;
    /** A plain LRC line has no end time; assume the last word rings out roughly this long. */
    private static final long LYRIC_TAIL_MILLIS = 1_500L;

    private AutomixPlanner() {
    }

    static AutomixPlan plan(AudioPlayer outgoing, AudioPlayer incoming) {
        return plan(outgoing, incoming, -1L);
    }

    /**
     * @param lastVocalEndMillis absolute position of the end of the outgoing track's last lyric line,
     *                           or a non-positive value when no timed lyrics are available. Mixing out
     *                           after the last vocal is the single biggest reason a blend sounds
     *                           seamless: two sets of vocals never collide.
     * @return the plan, or {@code null} only when the pair is fundamentally too short to blend
     */
    static AutomixPlan plan(AudioPlayer outgoing, AudioPlayer incoming, long lastVocalEndMillis) {
        if (outgoing == null || incoming == null) return null;
        if (!outgoing.isUsable() || !incoming.isUsable()) return null;

        long outgoingMillis = (long) outgoing.getTotalTimeMillis();
        long incomingMillis = (long) incoming.getTotalTimeMillis();
        if (outgoingMillis < MIN_OUTGOING_MILLIS || incomingMillis < MIN_INCOMING_MILLIS) return null;

        // The overlap can never exceed a quarter of the outgoing track or half of the incoming one,
        // otherwise a short track would be blended almost end to end.
        long overlap = AutomixSettings.getOverlapMillis();
        overlap = Math.min(overlap, outgoingMillis / 4L);
        overlap = Math.min(overlap, incomingMillis / 2L);
        if (overlap < 800L) return null;

        // Analysis reads the decoded cache files with their own handles. It must never read the live
        // JSyn samples: those stream through one shared sliding window, and moving it under the audio
        // thread stops playback dead (see PcmWindowReader).
        PcmWindowReader outgoingAudio = PcmWindowReader.open(outgoing.getSourceFile());
        PcmWindowReader incomingAudio = PcmWindowReader.open(incoming.getSourceFile());
        try {
            return plan(outgoingMillis, incomingMillis, overlap, lastVocalEndMillis,
                    outgoingAudio, incomingAudio);
        } finally {
            if (outgoingAudio != null) outgoingAudio.close();
            if (incomingAudio != null) incomingAudio.close();
        }
    }

    private static AutomixPlan plan(long outgoingMillis, long incomingMillis, long overlap,
                                    long lastVocalEndMillis, PcmWindowReader outgoingAudio,
                                    PcmWindowReader incomingAudio) {
        // Analysis is a bonus, not a requirement. A pair whose cache files cannot be parsed still gets
        // a plain equal-power crossfade rather than degrading to the old silent gap.
        boolean analysable = outgoingAudio != null && incomingAudio != null;
        int outgoingRate = analysable ? outgoingAudio.sampleRate() : 0;
        int incomingRate = analysable ? incomingAudio.sampleRate() : 0;
        if (outgoingRate <= 0 || incomingRate <= 0) analysable = false;
        boolean beatAlign = AutomixSettings.isBeatAlignEnabled();

        // -- Incoming side: skip a dead intro, then snap onto its first downbeat. --
        long cue = 0L;
        AutomixAnalyzer.BeatGrid incomingGrid = null;
        if (analysable) {
            long introScan = Math.min(MAX_INTRO_SKIP_MILLIS + BEAT_WINDOW_MILLIS, incomingMillis / 2L);
            float[] introWindow = incomingAudio.readMono(0L, introScan);
            if (introWindow.length > 0) {
                long introEnd = (long) AutomixAnalyzer.detectIntroEnd(introWindow, incomingRate);
                long introCap = Math.min(MAX_INTRO_SKIP_MILLIS,
                        (long) (incomingMillis * MAX_INTRO_SKIP_FRACTION));
                cue = Math.max(0L, Math.min(introCap, introEnd));
            }

            float[] incomingBeatWindow = incomingAudio.readMono(cue,
                    Math.min(BEAT_WINDOW_MILLIS, incomingMillis - cue));
            incomingGrid = AutomixAnalyzer.analyzeBeats(incomingBeatWindow, incomingRate);
            if (beatAlign && incomingGrid != null && incomingGrid.bpm > 1f) {
                long snapped = cue + (long) incomingGrid.barPhaseMillis;
                // Only accept the snap when it still leaves a full overlap of audio behind it.
                if (snapped + overlap < incomingMillis) cue = snapped;
            }
        }

        // -- Outgoing side: mix out where the song is done talking, not where the file ends. --
        long latestFire = outgoingMillis - overlap;
        long earliestFire = Math.max((long) (outgoingMillis * MIN_TRIM_POSITION),
                outgoingMillis - MAX_TRIM_MILLIS);
        if (earliestFire > latestFire) earliestFire = latestFire;

        long preferredFire = -1L;
        String mixOutReason = "尾部混出";
        if (lastVocalEndMillis > 0L) {
            long candidate = lastVocalEndMillis + LYRIC_TAIL_MILLIS;
            // Only trusted inside the window this track may be trimmed by. A lyric end far earlier than
            // that is not a long outro, it is bad data - a failed lyric load leaves the previous track's
            // timeline in place - and clamping it onto the boundary would silently cut the maximum
            // allowed amount off the end of every song.
            if (candidate >= earliestFire && candidate <= latestFire) {
                preferredFire = candidate;
                mixOutReason = "唱完后混出";
            }
        }
        if (preferredFire <= 0L && analysable) {
            long scanStart = Math.max(0L, latestFire - OUTRO_SCAN_MILLIS);
            float[] tail = outgoingAudio.readMono(scanStart, outgoingMillis - scanStart);
            float landing = AutomixAnalyzer.detectOutroLanding(tail, outgoingRate);
            if (landing >= 0f) {
                // Measured from the audio itself, so a landing beyond the trim budget is real evidence
                // and only gets pulled back to the boundary.
                preferredFire = scanStart + (long) landing;
                mixOutReason = "尾奏混出";
            }
        }

        long fire = latestFire;
        if (preferredFire > 0L) {
            fire = Math.max(earliestFire, Math.min(latestFire, preferredFire));
        }

        // -- Land the handover on a bar boundary of the outgoing track. --
        AutomixAnalyzer.BeatGrid outgoingGrid = null;
        boolean barAligned = false;
        if (analysable) {
            // Analysed backwards from the mix-out point: a window that ran forwards would overrun the
            // end of the track exactly when the mix-out sits near it, and return nothing.
            long gridStart = Math.max(0L, fire - BEAT_WINDOW_MILLIS);
            outgoingGrid = AutomixAnalyzer.analyzeBeats(
                    outgoingAudio.readMono(gridStart, fire - gridStart), outgoingRate);
            if (beatAlign && outgoingGrid != null && outgoingGrid.bpm > 1f) {
                float barPeriod = outgoingGrid.barPeriodMillis();
                if (barPeriod > 200f && barPeriod < 8_000f) {
                    long firstBar = gridStart + (long) outgoingGrid.barPhaseMillis;
                    long bars = Math.round((fire - firstBar) / (double) barPeriod);
                    long candidate = firstBar + (long) (bars * barPeriod);
                    if (candidate >= earliestFire && candidate <= latestFire && candidate > 0L) {
                        fire = candidate;
                        barAligned = true;
                    }
                }
            }
        }

        // -- Loudness match across the two zones that will actually overlap. --
        float gain = 1f;
        if (analysable) {
            float[] outgoingZone = outgoingAudio.readMono(fire, Math.min(overlap, outgoingMillis - fire));
            float[] incomingZone = incomingAudio.readMono(cue, Math.min(overlap, incomingMillis - cue));
            // K-weighted first: it is what the ear does. RMS is only the fallback for a window the
            // gating rejects, e.g. an overlap that lands on a near-silent tail.
            float outgoingLufs = AutomixAnalyzer.kWeightedLoudness(outgoingZone, outgoingRate);
            float incomingLufs = AutomixAnalyzer.kWeightedLoudness(incomingZone, incomingRate);
            if (!Float.isNaN(outgoingLufs) && !Float.isNaN(incomingLufs)) {
                gain = (float) Math.pow(10.0, (outgoingLufs - incomingLufs) / 20.0);
            } else {
                float outgoingRms = AutomixAnalyzer.rms(outgoingZone);
                float incomingRms = AutomixAnalyzer.rms(incomingZone);
                if (outgoingRms > .001f && incomingRms > .001f) {
                    gain = outgoingRms / incomingRms;
                }
            }
            gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, gain));
        }

        // -- Vibe check: only call it beat matched when the tempos really are close. --
        boolean tempoMatched = false;
        if (outgoingGrid != null && incomingGrid != null && outgoingGrid.bpm > 1f && incomingGrid.bpm > 1f) {
            float normalisedIncoming = AutomixAnalyzer.octaveNormalise(incomingGrid.bpm, outgoingGrid.bpm);
            float ratio = outgoingGrid.bpm / normalisedIncoming;
            tempoMatched = ratio >= MIN_TEMPO_RATIO && ratio <= MAX_TEMPO_RATIO;
        }

        // -- Tempo lock: bend the deck that is leaving onto the tempo of the one that is arriving. --
        float tempoRatio = 1f;
        if (tempoMatched && AutomixSettings.isTempoLockEnabled()
                && outgoingGrid != null && incomingGrid != null) {
            float normalisedIncoming = AutomixAnalyzer.octaveNormalise(incomingGrid.bpm, outgoingGrid.bpm);
            float desired = normalisedIncoming / outgoingGrid.bpm;
            tempoRatio = Math.max(MIN_RATE, Math.min(MAX_RATE, desired));
            if (Math.abs(tempoRatio - 1f) < MIN_USEFUL_RATE_DELTA) tempoRatio = 1f;
        }
        boolean bassSwap = AutomixSettings.isBassSwapEnabled();

        boolean beatMatched = barAligned && tempoMatched;
        String summary = describe(overlap, cue, gain, beatMatched, mixOutReason, outgoingGrid,
                incomingGrid, bassSwap, tempoRatio);
        System.out.println("[Automix] plan: fire=" + fire + "/" + outgoingMillis + "ms overlap=" + overlap
                + "ms cue=" + cue + "ms gain=" + String.format(Locale.ROOT, "%.2f", gain)
                + " (" + mixOutReason + (beatMatched ? ", 小节对齐" : "") + (bassSwap ? ", 低频交换" : "")
                + (tempoRatio == 1f ? "" : String.format(Locale.ROOT, ", 速率 %.3f", tempoRatio))
                + (analysable ? "" : ", 无分析") + ")");
        return new AutomixPlan(fire, overlap, cue, gain, beatMatched, bassSwap, tempoRatio, summary);
    }

    private static String describe(long overlap, long cue, float gain, boolean beatMatched,
                                   String mixOutReason,
                                   AutomixAnalyzer.BeatGrid outgoingGrid,
                                   AutomixAnalyzer.BeatGrid incomingGrid,
                                   boolean bassSwap, float tempoRatio) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.ROOT, "%.1fs 重叠", overlap / 1000f));
        builder.append(" · ").append(mixOutReason);
        if (beatMatched && outgoingGrid != null && incomingGrid != null) {
            builder.append(" · 小节对齐 ")
                    .append(Math.round(outgoingGrid.bpm)).append("→").append(Math.round(incomingGrid.bpm))
                    .append(" BPM");
        } else {
            builder.append(" · 等功率淡化");
        }
        if (cue > 400L) {
            builder.append(String.format(Locale.ROOT, " · 跳过前奏 %.1fs", cue / 1000f));
        }
        if (bassSwap) {
            builder.append(" · 低频交换");
        }
        if (tempoRatio != 1f) {
            builder.append(String.format(Locale.ROOT, " · 节奏锁定 %+.1f%%", (tempoRatio - 1f) * 100f));
        }
        if (Math.abs(gain - 1f) > .08f) {
            builder.append(String.format(Locale.ROOT, " · 音量补偿 %.2fx", gain));
        }
        return builder.toString();
    }
}
