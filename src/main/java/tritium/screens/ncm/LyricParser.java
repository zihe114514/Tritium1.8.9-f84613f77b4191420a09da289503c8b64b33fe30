package tritium.screens.ncm;

import com.google.gson.JsonObject;
import tritium.utils.Tuple;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricParser {

    public static List<LyricLine> parse(JsonObject input) {
        if (input.has("uncollected") || !input.has("lrc")) {
            return new ArrayList<>();
        }

        List<LyricLine> lyricLines = new ArrayList<>(parseSingleLine(replace(input.getAsJsonObject("lrc").get("lyric").getAsString())));

        processTranslationLyrics(input, lyricLines);
        processRomanizationLyrics(input, lyricLines);

        if (input.has("yrc")) {
            String yrc = replace(input.getAsJsonObject("yrc").get("lyric").getAsString());
            parseYrc(yrc, lyricLines);
            processTranslationLyricsYRC(input, lyricLines);
            processRomanizationLyricsYRC(input, lyricLines);
        }

        return lyricLines;
    }

    private static String replace(String input) {
        //        System.out.println(s);
        return input.replace(' ', ' ').replaceAll(" {2,}", " ");
    }

    private static void processTranslationLyricsYRC(JsonObject input, List<LyricLine> lyricLines) {
        if (!input.has("ytlrc")) return;

        String tLyric = input.getAsJsonObject("ytlrc").get("lyric").getAsString();
        if (tLyric.trim().isEmpty()) return;

        List<LyricLine> translates = parseSingleLine(tLyric);

        Map<Long, String> transMap = new HashMap<>();
        for (LyricLine t : translates) {
            transMap.put(t.timestamp, t.lyric);
        }

        for (LyricLine l : lyricLines) {
            String translation = transMap.get(l.timestamp);

            if (translation == null) {
                System.out.println("Translation not found for " + l.lyric + " at " + l.timestamp);
                continue;
            }

            if (l.translationText == null) {
                l.translationText = translation;
            }
        }
    }

    private static void processRomanizationLyricsYRC(JsonObject input, List<LyricLine> lyricLines) {
        if (!input.has("yromalrc")) return;

        String romanization = input.getAsJsonObject("yromalrc").get("lyric").getAsString();
        if (romanization.isEmpty()) return;

        List<LyricLine> romanizations = parseSingleLine(romanization);

        Map<Long, String> romaMap = new HashMap<>();
        for (LyricLine r : romanizations) {
            romaMap.put(r.timestamp, r.lyric);
        }

        for (LyricLine l : lyricLines) {
            String roma = romaMap.get(l.timestamp);
            if (roma != null && l.romanizationText == null) {
                l.romanizationText = roma;
            }
        }
    }

    private static void processTranslationLyrics(JsonObject input, List<LyricLine> lyricLines) {
        if (!input.has("tlyric")) return;

        String tLyric = replace(input.getAsJsonObject("tlyric").get("lyric").getAsString());
        if (tLyric.trim().isEmpty()) return;

        List<LyricLine> translates = parseSingleLine(tLyric);

        Map<Long, String> transMap = new HashMap<>();
        for (LyricLine t : translates) {
            transMap.put(t.timestamp, t.lyric);
        }

        for (LyricLine l : lyricLines) {
            String translation = transMap.get(l.timestamp);
            if (translation != null && l.translationText == null) {
                l.translationText = translation;
            }
        }
    }

    private static void processRomanizationLyrics(JsonObject input, List<LyricLine> lyricLines) {
        if (!input.has("romalrc")) return;

        String romanization = replace(input.getAsJsonObject("romalrc").get("lyric").getAsString());
        if (romanization.isEmpty()) return;

        List<LyricLine> romanizations = parseSingleLine(romanization);

        Map<Long, String> romaMap = new HashMap<>();
        for (LyricLine r : romanizations) {
            romaMap.put(r.timestamp, r.lyric);
        }

        for (LyricLine l : lyricLines) {
            String roma = romaMap.get(l.timestamp);
            if (roma != null && l.romanizationText == null) {
                l.romanizationText = roma;
            }
        }
    }

    private static List<LyricLine> parseSingleLine(String input) {
        List<LyricLine> lyricLines = new ArrayList<>();
        String[] lines = input.split("\\n");
        if (lines.length == 1) lines = input.split("\\\\n");

        for (String line : lines) {
            List<LyricLine> parsedLines = parseLine(line);
            if (parsedLines != null) {
                lyricLines.addAll(parsedLines);
            }
        }

        lyricLines.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        return lyricLines;
    }

    private static List<LyricLine> parseLine(String input) {
        if (input.isEmpty()) {
            return null;
        }
        boolean alt = false;
        input = input.trim();
//        System.out.println("input = " + input);
        Matcher lineMatcher = Pattern.
                compile("((?:\\[\\d{2}:\\d{2}\\.\\d{2,3}])+)(.*)").matcher(input);
        if (!lineMatcher.matches()) {
            lineMatcher = Pattern.
                    compile("((?:\\[\\d{2}:\\d{2}:\\d{2,3}])+)(.*)").matcher(input);
            if (!lineMatcher.matches()) {
                return null;
            }
            alt = true;
        }
        String times = lineMatcher.group(1);
        String text = lineMatcher.group(2).trim();
        if (text.isEmpty()) {
            return null;
        }

        List<LyricLine> entryList = new ArrayList<>();
        Matcher timeMatcher = Pattern.compile(alt ? "\\[(\\d\\d):(\\d\\d):(\\d{2,3})]" : "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})]").matcher(times);
        while (timeMatcher.find()) {
            long min = Long.parseLong(timeMatcher.group(1));
            long sec = Long.parseLong(timeMatcher.group(2));
            String milStr = timeMatcher.group(3);
            
            long mil;
            if (milStr.length() == 3) {
                mil = Long.parseLong(milStr);
            } else {
                mil = Long.parseLong(milStr) * 10;
            }
            
            long time =
                    min * 60000 +
                            sec * 1000 +
                            mil;

            entryList.add(new LyricLine(time, text.replace("　", " ")));
        }
        return entryList;
    }

    public static void parseYrc(String yrc, List<LyricLine> lyricLines) {
        String[] lines = yrc.split("\n");
        if (lines.length == 1) lines = yrc.split("\\\\n");

        lyricLines.clear();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("[")) continue;

            // 解析时间信息
            String timeData = line.substring(1, line.indexOf("]"));
            String[] timeParts = timeData.split(",");
            long startDuration = Long.parseLong(timeParts[0]);
            long duration = Long.parseLong(timeParts[1]);

            if (i > 0 && startDuration == 0)
                continue;

            LyricLine l = new LyricLine(startDuration, "");
            l.duration = duration;

            parseWordTimings(l, line.substring(line.indexOf("]") + 1));

            StringBuilder sb = new StringBuilder();
            for (LyricLine.Word word : l.words) {
                sb.append(word.word);
            }

            l.lyric = sb.toString();

//            MusicLyricsWidget.ScrollTiming timing = new MusicLyricsWidget.ScrollTiming();
//            timing.start = startDuration;
//            timing.duration = duration;
//            timing.text = line.substring(line.indexOf("]") + 1);

//            if (timing.text.isEmpty())
//                continue;

//            parseWordTimings(timing);
//            MusicLyricsWidget.timings.add(timing);
            lyricLines.add(l);
        }
    }

    private static void parseWordTimings(LyricLine l, String text) {
        Pattern pattern = Pattern.compile("\\((\\d+),(\\d+),0\\)((?!\\(\\d+,\\d+,0\\)|\\(\\d+,\\d+,0\\\\).)*");
        Matcher matcher = pattern.matcher(text);

        List<Tuple<String, Tuple<Long, Long>>> words = new ArrayList<>();

        while (matcher.find()) {
            String group = matcher.group();
            String metadata = group.substring(1, group.indexOf(")") + 1);
            String[] metadataParts = metadata.split(",");
            String lyric = group.substring(group.indexOf(")") + 1);

            long timestamp = Long.parseLong(metadataParts[0]);
            long duration = Long.parseLong(metadataParts[1]);

            if (duration <= 0 && !words.isEmpty()) {
                // wtf
                Tuple<String, Tuple<Long, Long>> last = words.get(words.size() - 1);
                last.setA(last.getA() + lyric);
            } else {
                words.add(Tuple.of(lyric, new Tuple<>(timestamp, duration)));
            }
        }

        words.stream().map(
        t -> new LyricLine.Word(
                t.getA(),
                t.getB().getA(),
                t.getB().getB()
            )
        ).forEach(l.words::add);
    }
}
