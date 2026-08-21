package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Byte-level audio container detection shared by the playback cache pipeline.
 *
 * <p>Reported MIME types and URL suffixes are advisory only: callers must use
 * {@link #detectContainer(File)} before choosing a decoder.</p>
 */
final class AudioContainerSupport {

    private static final List<String> SUPPORTED_CONTAINERS =
            Arrays.asList("flac", "wav", "mp3", "aac", "m4a", "mp4");

    private AudioContainerSupport() {
    }

    static boolean isSupportedContainer(String container) {
        return container != null && SUPPORTED_CONTAINERS.contains(container);
    }

    static List<String> getSupportedContainers() {
        return SUPPORTED_CONTAINERS;
    }

    static boolean requiresAacDecode(String container) {
        return "aac".equals(container) || "m4a".equals(container) || "mp4".equals(container);
    }

    /**
     * Normalizes an API MIME type/extension, but callers must still inspect the file bytes before
     * playing it. Unknown values intentionally become {@code null}, so a valid CDN response with
     * a bad Content-Type can still be recognized after download.
     */
    static String normalizeReportedContainer(String reportedType) {
        if (reportedType == null) {
            return null;
        }
        String type = reportedType.trim().toLowerCase();
        int parameterIndex = type.indexOf(';');
        if (parameterIndex >= 0) {
            type = type.substring(0, parameterIndex).trim();
        }
        if (type.startsWith("audio/")) {
            type = type.substring("audio/".length());
        }
        if ("mpeg".equals(type) || "mpga".equals(type) || "x-mp3".equals(type)) {
            return "mp3";
        }
        if ("x-wav".equals(type) || "wave".equals(type)) {
            return "wav";
        }
        if ("x-flac".equals(type)) {
            return "flac";
        }
        if ("adts".equals(type) || "x-aac".equals(type)) {
            return "aac";
        }
        if ("mp4".equals(type) || "mp4a".equals(type) || "m4a".equals(type)) {
            return "m4a";
        }
        return isSupportedContainer(type) ? type : null;
    }

    /**
     * Identifies the real container from its bytes rather than trusting a URL suffix or API type.
     * AAC ADTS is checked before MPEG audio because both start with an {@code 0xFFF} sync word.
     */
    static String detectContainer(File file) {
        if (file == null || !file.isFile() || file.length() < 4L) {
            return null;
        }

        byte[] header = new byte[64];
        try (InputStream input = Files.newInputStream(file.toPath())) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }

            if (hasAscii(header, offset, 0, "fLaC")) {
                return "flac";
            }
            if (hasAscii(header, offset, 0, "RIFF") && hasAscii(header, offset, 8, "WAVE")) {
                return "wav";
            }
            if (hasAscii(header, offset, 0, "OggS")) {
                return "ogg";
            }
            if (isAsfHeader(header, offset)) {
                return "asf";
            }
            String isoBaseMediaContainer = detectIsoBaseMediaContainer(header, offset);
            if (isoBaseMediaContainer != null) {
                return isoBaseMediaContainer;
            }
            if (hasAscii(header, offset, 0, "ID3")) {
                return "mp3";
            }
            if (isAdtsAacHeader(header, offset)) {
                return "aac";
            }
            if (isMpegAudioHeader(header, offset)) {
                return "mp3";
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static boolean hasAscii(byte[] bytes, int length, int start, String value) {
        if (bytes == null || length < start + value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if ((bytes[start + index] & 0xFF) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static String detectIsoBaseMediaContainer(byte[] header, int length) {
        if (!hasAscii(header, length, 4, "ftyp")) {
            return null;
        }
        long boxSize = ((long) (header[0] & 0xFF) << 24)
                | ((long) (header[1] & 0xFF) << 16)
                | ((long) (header[2] & 0xFF) << 8)
                | (header[3] & 0xFF);
        if (boxSize < 16L || boxSize > length) {
            return "mp4";
        }

        // Major brand is at byte 8; compatible brands start at byte 16.
        for (int brandOffset = 8; brandOffset + 4 <= boxSize; brandOffset += 4) {
            if (hasIsoAudioBrand(header, length, brandOffset)) {
                return "m4a";
            }
        }
        return "mp4";
    }

    private static boolean hasIsoAudioBrand(byte[] header, int length, int offset) {
        return hasAscii(header, length, offset, "M4A ")
                || hasAscii(header, length, offset, "M4B ")
                || hasAscii(header, length, offset, "M4P ")
                || hasAscii(header, length, offset, "mp4a");
    }

    private static boolean isAdtsAacHeader(byte[] header, int length) {
        if (length < 7 || (header[0] & 0xFF) != 0xFF || (header[1] & 0xF0) != 0xF0) {
            return false;
        }
        // ADTS has a zero Layer field; MPEG audio has a non-zero Layer field.
        if ((header[1] & 0x06) != 0) {
            return false;
        }
        int frequencyIndex = (header[2] >>> 2) & 0x0F;
        if (frequencyIndex == 0x0F) {
            return false;
        }
        int frameLength = ((header[3] & 0x03) << 11)
                | ((header[4] & 0xFF) << 3)
                | ((header[5] >>> 5) & 0x07);
        return frameLength >= 7;
    }

    private static boolean isMpegAudioHeader(byte[] header, int length) {
        if (length < 4 || (header[0] & 0xFF) != 0xFF || (header[1] & 0xE0) != 0xE0) {
            return false;
        }
        int layer = (header[1] >>> 1) & 0x03;
        int bitrateIndex = (header[2] >>> 4) & 0x0F;
        return layer != 0 && bitrateIndex != 0 && bitrateIndex != 0x0F;
    }

    private static boolean isAsfHeader(byte[] header, int length) {
        int[] asfHeaderGuid = {0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
                0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C};
        if (length < asfHeaderGuid.length) {
            return false;
        }
        for (int index = 0; index < asfHeaderGuid.length; index++) {
            if ((header[index] & 0xFF) != asfHeaderGuid[index]) {
                return false;
            }
        }
        return true;
    }
}
