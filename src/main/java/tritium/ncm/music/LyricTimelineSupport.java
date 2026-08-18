package tritium.ncm.music;

import tritium.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure construction and lookup rules for a parsed lyric timeline.
 *
 * <p>It does not access playback, rendering or GUI state. {@link CloudMusic}
 * remains the compatibility facade and publishes the prepared result to its
 * existing thread-safe lyric list.</p>
 */
final class LyricTimelineSupport {

    private static final long LONG_BREAK_DURATION_MILLIS = 3000L;
    private static final float JUMP_TO_NEXT_MILLIS = 300.0f;

    private LyricTimelineSupport() {
    }

    static PreparedTimeline prepare(List<LyricLine> parsedLyrics) {
        List<LyricLine> timeline = new ArrayList<>();
        timeline.addAll(parsedLyrics);
        if (timeline.isEmpty()) {
            timeline.add(new LyricLine(0L, "暂无歌词"));
        }

        boolean haveNoWords = hasNoWords(timeline);
        addLongBreaks(timeline, haveNoWords);
        return new PreparedTimeline(timeline, haveNoWords);
    }

    static LyricLine findCurrentLyric(List<LyricLine> timeline, boolean haveNoWords, double songProgress) {
        for (int index = 0; index < timeline.size(); index++) {
            LyricLine lyric = timeline.get(index);
            LyricLine previous = index > 0 ? timeline.get(index - 1) : null;

            if (!haveNoWords
                    && !lyric.isBreakLine
                    && lyric.getTimestamp() > songProgress
                    && lyric.getTimestamp() - songProgress <= JUMP_TO_NEXT_MILLIS
                    && canJumpToNextEarly(previous)) {
                return lyric;
            }

            if (lyric.getTimestamp() > songProgress) {
                return index > 0 ? timeline.get(index - 1) : timeline.get(0);
            }

            if (index == timeline.size() - 1) {
                return lyric;
            }
        }
        return timeline.isEmpty() ? null : timeline.get(0);
    }

    private static boolean hasNoWords(List<LyricLine> timeline) {
        for (LyricLine lyric : timeline) {
            if (!lyric.words.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void addLongBreaks(List<LyricLine> timeline, boolean haveNoWords) {
        if (haveNoWords) {
            addInitialBreakIfNeeded(timeline, LONG_BREAK_DURATION_MILLIS);
            return;
        }
        addBreaksBetweenLyrics(timeline, LONG_BREAK_DURATION_MILLIS);
    }

    private static void addInitialBreakIfNeeded(List<LyricLine> timeline, long duration) {
        long firstTimestamp = timeline.get(0).getTimestamp();
        if (firstTimestamp >= duration) {
            timeline.add(createBreakLine(0L, firstTimestamp));
            timeline.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        }
    }

    private static void addBreaksBetweenLyrics(List<LyricLine> timeline, long duration) {
        long lastTimestamp = 0L;
        List<LyricLine> breaksToAdd = new ArrayList<>();
        for (LyricLine line : timeline) {
            long lineDuration = line.duration;
            long gap = line.getTimestamp() - lastTimestamp;
            if (gap >= duration) {
                breaksToAdd.add(createBreakLine(lastTimestamp, gap));
            }
            lastTimestamp = line.getTimestamp() + lineDuration;
        }
        timeline.addAll(breaksToAdd);
        timeline.sort(Comparator.comparingLong(LyricLine::getTimestamp));
    }

    private static LyricLine createBreakLine(long timestamp, long duration) {
        LyricLine line = new LyricLine(timestamp, "● ● ●");
        line.isBreakLine = true;
        line.words.add(new LyricLine.Word("● ● ●", timestamp, duration));
        return line;
    }

    private static boolean canJumpToNextEarly(LyricLine lyric) {
        return lyric != null && !lyric.words.isEmpty() && lyric.duration >= JUMP_TO_NEXT_MILLIS;
    }

    static final class PreparedTimeline {
        final List<LyricLine> lines;
        final boolean haveNoWords;

        private PreparedTimeline(List<LyricLine> lines, boolean haveNoWords) {
            this.lines = lines;
            this.haveNoWords = haveNoWords;
        }
    }
}
