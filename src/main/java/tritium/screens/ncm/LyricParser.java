package tritium.screens.ncm;

import com.google.gson.JsonObject;
import top.fpsmaster.music.Lyric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 歌词解析与跨平台时间轴适配。 */
public class LyricParser {

    private static final Pattern LRC_LINE = Pattern.compile("((?:\\[\\d{1,3}:\\d{1,2}[.:]\\d{1,3}])+)(.*)");
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})[.:](\\d{1,3})]");
    private static final Pattern ENHANCED_LRC_WORD = Pattern.compile("<(\\d{1,3}):(\\d{1,2})[.:](\\d{1,3})>([^<]*)");
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+)(?:,(\\d+))?\\)([^()]*)");

    public static List<LyricLine> parse(JsonObject input) {
        if (input == null || input.has("uncollected")) return new ArrayList<>();

        String lrc = readLyric(input, "lrc");
        String translated = firstNonEmpty(readLyric(input, "ytlrc"), readLyric(input, "tlyric"));
        String yrc = readLyric(input, "yrc");
        if (lrc.trim().isEmpty() && yrc.trim().isEmpty()) return new ArrayList<>();

        // 真实逐字时间轴必须先于普通 LRC 解析，否则普通整句结果会吞掉 YRC/增强 LRC。
        List<LyricLine> timedLines = new ArrayList<>();
        if (!yrc.trim().isEmpty()) parseYrc(replace(yrc), timedLines);
        if (timedLines.isEmpty() && looksLikeYrc(lrc)) parseYrc(replace(lrc), timedLines);
        if (timedLines.isEmpty() && containsEnhancedTiming(lrc)) timedLines = parseEnhancedLrc(replace(lrc));
        if (!timedLines.isEmpty()) {
            applySecondary(parseSingleLine(translated), timedLines, true);
            applyRomanization(input, timedLines);
            return timedLines;
        }

        // 统一 API 模型仍负责普通 LRC 与平台翻译；这里只接受真实逐字数据，不再伪造字符时间轴。
        try {
            List<top.fpsmaster.music.LyricLine> remote = top.fpsmaster.music.lyric.LyricParser.INSTANCE
                    .parse(replace(lrc), replace(translated), "");
            Lyric cadenceLyric = new Lyric(lrc, translated, "", remote);
            List<LyricLine> converted = fromCadence(cadenceLyric, 0L, false);
            if (!converted.isEmpty()) {
                applyRomanization(input, converted);
                return converted;
            }
        } catch (Throwable ignored) {
        }

        List<LyricLine> lyricLines = new ArrayList<>(parseSingleLine(replace(lrc)));
        processTranslationLyrics(input, lyricLines);
        processRomanizationLyrics(input, lyricLines);
        inferLineDurations(lyricLines, 0L);
        return lyricLines;
    }

    /** 把 Cadence 的统一歌词模型转换为项目现有渲染模型。 */
    public static List<LyricLine> fromCadence(Lyric lyric, long trackDurationMs, boolean estimateMissingWords) {
        List<LyricLine> result = new ArrayList<>();
        if (lyric == null || lyric.getLines() == null) return result;

        for (top.fpsmaster.music.LyricLine source : lyric.getLines()) {
            if (source == null || source.isMetadata()) continue;
            String text = replace(source.getText()).trim();
            if (text.isEmpty()) continue;

            LyricLine line = new LyricLine(Math.max(0L, source.getStartMs()), text);
            line.duration = Math.max(0L, source.getDurationMs());
            if (source.getTranslation() != null && !source.getTranslation().trim().isEmpty()) {
                line.translationText = replace(source.getTranslation()).trim();
            }

            List<top.fpsmaster.music.LyricWord> sourceWords = source.getWords();
            if (sourceWords != null && !sourceWords.isEmpty()) {
                List<TimedText> words = new ArrayList<>();
                String pendingPrefix = "";
                for (top.fpsmaster.music.LyricWord sourceWord : sourceWords) {
                    if (sourceWord == null || sourceWord.getText() == null) continue;
                    String wordText = replace(sourceWord.getText());
                    if (wordText.isEmpty()) continue;
                    long duration = sourceWord.getDurationMs();
                    if (duration <= 0L) {
                        if (!words.isEmpty()) words.get(words.size() - 1).text += wordText;
                        else pendingPrefix += wordText;
                        continue;
                    }
                    long start = normalizeWordStart(line.timestamp, line.duration, sourceWord.getStartMs());
                    words.add(new TimedText(pendingPrefix + wordText, start, duration));
                    pendingPrefix = "";
                }
                if (!pendingPrefix.isEmpty() && !words.isEmpty()) words.get(words.size() - 1).text += pendingPrefix;
                addWords(line, words);
            }
            result.add(line);
        }

        result.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        inferLineDurations(result, trackDurationMs);
        normalizeWordDurations(result);

        // 参数仅为二进制兼容保留。普通 LRC 不再按字数平均切分，避免“假逐字歌词”。
        return result;
    }

    public static boolean hasRealWordTiming(List<LyricLine> lines) {
        if (lines == null) return false;
        for (LyricLine line : lines) {
            if (line != null && line.hasTimedWords()) return true;
        }
        return false;
    }

    private static String readLyric(JsonObject input, String key) {
        try {
            if (input.has(key) && input.get(key).isJsonObject()) {
                JsonObject obj = input.getAsJsonObject(key);
                if (obj.has("lyric") && !obj.get("lyric").isJsonNull()) return obj.get("lyric").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : (second == null ? "" : second);
    }

    private static String replace(String input) {
        if (input == null) return "";
        return input.replace('\u00a0', ' ').replaceAll("[\\u2000-\\u200A]", " ").replaceAll(" {2,}", " ");
    }

    private static void processTranslationLyrics(JsonObject input, List<LyricLine> lyricLines) {
        applySecondary(parseSingleLine(firstNonEmpty(readLyric(input, "ytlrc"), readLyric(input, "tlyric"))), lyricLines, true);
    }

    private static void processRomanizationLyrics(JsonObject input, List<LyricLine> lyricLines) {
        applyRomanization(input, lyricLines);
    }

    private static void applyRomanization(JsonObject input, List<LyricLine> lyricLines) {
        String roman = firstNonEmpty(readLyric(input, "yromalrc"), readLyric(input, "romalrc"));
        applySecondary(parseSingleLine(roman), lyricLines, false);
    }

    private static void applySecondary(List<LyricLine> secondary, List<LyricLine> lyricLines, boolean translation) {
        Map<Long, String> map = new HashMap<>();
        for (LyricLine line : secondary) map.put(line.timestamp, line.lyric);
        for (LyricLine line : lyricLines) {
            String value = map.get(line.timestamp);
            if (value == null) value = findNearbySecondary(map, line.timestamp, 80L);
            if (value == null || value.isEmpty()) continue;
            if (translation && line.translationText == null) line.translationText = value;
            if (!translation && line.romanizationText == null) line.romanizationText = value;
        }
    }

    private static String findNearbySecondary(Map<Long, String> values, long timestamp, long toleranceMs) {
        String nearest = null;
        long distance = toleranceMs + 1L;
        for (Map.Entry<Long, String> entry : values.entrySet()) {
            long candidateDistance = Math.abs(entry.getKey() - timestamp);
            if (candidateDistance < distance) {
                distance = candidateDistance;
                nearest = entry.getValue();
            }
        }
        return nearest;
    }

    private static List<LyricLine> parseSingleLine(String input) {
        List<LyricLine> result = new ArrayList<>();
        if (input == null || input.trim().isEmpty()) return result;
        String[] lines = input.split("\\r?\\n");
        if (lines.length == 1) lines = input.split("\\\\n");
        for (String line : lines) {
            List<LyricLine> parsed = parseLine(line);
            if (parsed != null) result.addAll(parsed);
        }
        result.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        return result;
    }

    private static List<LyricLine> parseLine(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        Matcher lineMatcher = LRC_LINE.matcher(input.trim());
        if (!lineMatcher.matches()) return null;

        String times = lineMatcher.group(1);
        String text = replace(lineMatcher.group(2)).trim();
        if (text.isEmpty()) return null;

        List<LyricLine> entryList = new ArrayList<>();
        Matcher timeMatcher = LRC_TIME.matcher(times);
        while (timeMatcher.find()) entryList.add(new LyricLine(parseTimestamp(timeMatcher), text.replace("　", " ")));
        return entryList;
    }

    private static boolean containsEnhancedTiming(String lrc) {
        return lrc != null && ENHANCED_LRC_WORD.matcher(lrc).find();
    }

    private static boolean looksLikeYrc(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String[] lines = text.split("\\r?\\n", 2);
        return YRC_LINE.matcher(lines[0].trim()).matches() && YRC_WORD.matcher(lines[0]).find();
    }

    private static List<LyricLine> parseEnhancedLrc(String lrc) {
        List<LyricLine> result = new ArrayList<>();
        if (lrc == null || lrc.trim().isEmpty()) return result;
        String[] rawLines = lrc.split("\\r?\\n");
        if (rawLines.length == 1) rawLines = lrc.split("\\\\n");

        for (String rawLine : rawLines) {
            Matcher lineMatcher = LRC_LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) continue;
            Matcher lineTime = LRC_TIME.matcher(lineMatcher.group(1));
            if (!lineTime.find()) continue;

            long lineStart = parseTimestamp(lineTime);
            Matcher wordMatcher = ENHANCED_LRC_WORD.matcher(lineMatcher.group(2));
            List<TimedText> tags = new ArrayList<>();
            while (wordMatcher.find()) {
                long wordStart = parseTimestamp(wordMatcher.group(1), wordMatcher.group(2), wordMatcher.group(3));
                tags.add(new TimedText(replace(wordMatcher.group(4)), Math.max(lineStart, wordStart), 0L));
            }
            if (tags.isEmpty()) continue;

            List<TimedText> words = new ArrayList<>();
            StringBuilder lyricText = new StringBuilder();
            for (int i = 0; i < tags.size(); i++) {
                TimedText tag = tags.get(i);
                if (tag.text.isEmpty()) continue;
                long nextStart = i + 1 < tags.size() ? tags.get(i + 1).start : tag.start;
                long wordDuration = Math.max(0L, nextStart - tag.start);
                words.add(new TimedText(tag.text, tag.start, wordDuration));
                lyricText.append(tag.text);
            }
            if (words.isEmpty()) continue;

            LyricLine line = new LyricLine(lineStart, lyricText.toString());
            TimedText lastTag = tags.get(tags.size() - 1);
            if (lastTag.text.isEmpty() && lastTag.start > lineStart) line.duration = lastTag.start - lineStart;
            addWords(line, words);
            result.add(line);
        }

        result.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        inferLineDurations(result, 0L);
        normalizeWordDurations(result);
        return result;
    }

    public static void parseYrc(String yrc, List<LyricLine> lyricLines) {
        lyricLines.clear();
        if (yrc == null || yrc.trim().isEmpty()) return;
        String[] lines = yrc.split("\\r?\\n");
        if (lines.length == 1) lines = yrc.split("\\\\n");

        for (String rawLine : lines) {
            Matcher lineMatcher = YRC_LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) continue;
            long start = Long.parseLong(lineMatcher.group(1));
            long duration = Long.parseLong(lineMatcher.group(2));
            String content = lineMatcher.group(3);

            List<TimedText> words = new ArrayList<>();
            String pendingPrefix = "";
            Matcher wordMatcher = YRC_WORD.matcher(content);
            while (wordMatcher.find()) {
                String wordText = replace(wordMatcher.group(4));
                if (wordText.isEmpty()) continue;
                long wordStart = Long.parseLong(wordMatcher.group(1));
                long wordDuration = Long.parseLong(wordMatcher.group(2));

                // 网易云 YRC 中 (0,0,0) 常用于把空格/标点续接到前一个词，不能生成时间戳为 0 的假词。
                if (wordDuration <= 0L) {
                    if (!words.isEmpty()) words.get(words.size() - 1).text += wordText;
                    else pendingPrefix += wordText;
                    continue;
                }

                long absoluteStart = normalizeWordStart(start, duration, wordStart);
                words.add(new TimedText(pendingPrefix + wordText, absoluteStart, wordDuration));
                pendingPrefix = "";
            }
            if (!pendingPrefix.isEmpty() && !words.isEmpty()) words.get(words.size() - 1).text += pendingPrefix;
            if (words.isEmpty()) continue;

            StringBuilder text = new StringBuilder();
            for (TimedText word : words) text.append(word.text);
            LyricLine line = new LyricLine(start, text.toString());
            line.duration = Math.max(0L, duration);
            addWords(line, words);
            lyricLines.add(line);
        }
        lyricLines.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        inferLineDurations(lyricLines, 0L);
        normalizeWordDurations(lyricLines);
    }

    private static long normalizeWordStart(long lineStart, long lineDuration, long wordStart) {
        if (wordStart >= lineStart) return wordStart;
        // 一些增强歌词使用相对行首毫秒，网易云 YRC 通常使用绝对毫秒；兼容两者。
        if (wordStart >= 0L && (lineDuration <= 0L || wordStart <= lineDuration + 1000L)) return lineStart + wordStart;
        return lineStart;
    }

    private static void addWords(LyricLine line, List<TimedText> words) {
        for (TimedText word : words) {
            if (word.text != null && !word.text.isEmpty()) {
                line.words.add(new LyricLine.Word(word.text, Math.max(0L, word.start), Math.max(0L, word.duration)));
            }
        }
    }

    private static void inferLineDurations(List<LyricLine> lines, long trackDurationMs) {
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            long next = i + 1 < lines.size() ? lines.get(i + 1).timestamp : trackDurationMs;
            if (line.duration <= 0L && next > line.timestamp) line.duration = next - line.timestamp;
            if (line.duration <= 0L) line.duration = Math.max(1200L, line.lyric.codePointCount(0, line.lyric.length()) * 180L);
        }
    }

    private static void normalizeWordDurations(List<LyricLine> lines) {
        for (LyricLine line : lines) {
            if (line.words.isEmpty()) continue;
            List<LyricLine.Word> normalized = new ArrayList<>();
            long lineEnd = line.timestamp + Math.max(1L, line.duration);
            for (int i = 0; i < line.words.size(); i++) {
                LyricLine.Word word = line.words.get(i);
                long start = Math.max(line.timestamp, word.timestamp);
                long nextStart = i + 1 < line.words.size() ? Math.max(start + 1L, line.words.get(i + 1).timestamp) : lineEnd;
                long duration = word.duration > 0L ? word.duration : nextStart - start;
                if (lineEnd > start) duration = Math.min(duration, lineEnd - start);
                normalized.add(new LyricLine.Word(word.word, start, Math.max(1L, duration)));
            }
            line.words.clear();
            line.words.addAll(normalized);
        }
    }

    private static long parseTimestamp(Matcher matcher) {
        return parseTimestamp(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private static long parseTimestamp(String minutes, String seconds, String fraction) {
        long millis = Long.parseLong(fraction);
        if (fraction.length() == 1) millis *= 100L;
        else if (fraction.length() == 2) millis *= 10L;
        return Long.parseLong(minutes) * 60000L + Long.parseLong(seconds) * 1000L + millis;
    }

    private static final class TimedText {
        private String text;
        private final long start;
        private final long duration;

        private TimedText(String text, long start, long duration) {
            this.text = text;
            this.start = start;
            this.duration = duration;
        }
    }
}
