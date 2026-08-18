package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds responsive physical KTV rows while preserving each word's original timing.
 * Rendering, colour, glow and playback state remain in {@link MusicLyricsWidget}.
 */
final class KaraokeLayoutBuilder {

    private KaraokeLayoutBuilder() {
    }

    static Layout build(CFontRenderer font, LyricLine line, double availableWidth) {
        List<String> physicalLines = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        double currentWidth = 0.0;

        for (LyricLine.Word word : line.words) {
            String text = word.word;
            if (text == null || text.isEmpty()) {
                continue;
            }

            double wordWidth = font.getStringWidthD(text);
            if (wordWidth <= availableWidth) {
                if (currentLine.length() > 0 && currentWidth + wordWidth > availableWidth) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }
                segments.add(new Segment(text, word, physicalLines.size(), currentWidth, wordWidth,
                        0, text.codePointCount(0, text.length())));
                currentLine.append(text);
                currentWidth += wordWidth;
                continue;
            }

            int start = 0;
            while (start < text.length()) {
                if (currentLine.length() > 0 && currentWidth >= availableWidth - .01) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }

                int end = findFragmentEnd(font, text, start, availableWidth - currentWidth);
                if (end <= start) {
                    if (currentLine.length() > 0) {
                        physicalLines.add(currentLine.toString());
                        currentLine.setLength(0);
                        currentWidth = 0.0;
                        continue;
                    }
                    end = text.offsetByCodePoints(start, 1);
                }

                String fragment = text.substring(start, end);
                double fragmentWidth = font.getStringWidthD(fragment);
                segments.add(new Segment(fragment, word, physicalLines.size(), currentWidth, fragmentWidth,
                        text.codePointCount(0, start), fragment.codePointCount(0, fragment.length()),
                        text.codePointCount(0, text.length())));
                currentLine.append(fragment);
                currentWidth += fragmentWidth;
                start = end;

                if (start < text.length()) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }
            }
        }

        if (currentLine.length() > 0 || physicalLines.isEmpty()) {
            physicalLines.add(currentLine.toString());
        }
        return new Layout(physicalLines.toArray(new String[0]), segments);
    }

    static String[] fitText(CFontRenderer font, String text, double availableWidth) {
        if (text == null || text.isEmpty()) {
            return new String[]{""};
        }
        String[] fitted = font.fitWidth(text, availableWidth);
        return fitted == null || fitted.length == 0 ? new String[]{text} : fitted;
    }

    private static int findFragmentEnd(CFontRenderer font, String text, int start, double availableWidth) {
        int end = start;
        int candidate = start;
        while (candidate < text.length()) {
            int next = text.offsetByCodePoints(candidate, 1);
            if (font.getStringWidthD(text.substring(start, next)) > availableWidth) {
                break;
            }
            end = next;
            candidate = next;
        }
        return end;
    }

    static final class Layout {
        final String[] primaryLines;
        final List<Segment> segments;

        private Layout(String[] primaryLines, List<Segment> segments) {
            this.primaryLines = primaryLines;
            this.segments = segments;
        }
    }

    static final class Segment {
        final String text;
        final LyricLine.Word word;
        final int lineIndex;
        final double offsetX;
        final double width;
        final int characterOffset;
        final int characterCount;
        final int totalCharacterCount;

        private Segment(String text, LyricLine.Word word, int lineIndex, double offsetX, double width,
                        int characterOffset, int characterCount) {
            this(text, word, lineIndex, offsetX, width, characterOffset, characterCount, characterCount);
        }

        private Segment(String text, LyricLine.Word word, int lineIndex, double offsetX, double width,
                        int characterOffset, int characterCount, int totalCharacterCount) {
            this.text = text;
            this.word = word;
            this.lineIndex = lineIndex;
            this.offsetX = offsetX;
            this.width = width;
            this.characterOffset = characterOffset;
            this.characterCount = Math.max(1, characterCount);
            this.totalCharacterCount = Math.max(this.characterCount, totalCharacterCount);
        }
    }
}