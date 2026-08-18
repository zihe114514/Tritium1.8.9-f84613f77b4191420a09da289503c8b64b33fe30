package tritium.ncm.music;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads time-synchronised lyric text stored inside common local-audio metadata containers.
 *
 * <p>The reader deliberately returns raw lyric text only.  The existing {@code LyricParser}
 * remains the single authority for LRC/YRC/enhanced-LRC timing and rendering semantics.</p>
 */
final class EmbeddedLyricsReader {
    private static final int MAX_TAG_BYTES = 2 * 1024 * 1024;
    private static final String MP4_LYRICS_ATOM = "\u00A9lyr";

    private EmbeddedLyricsReader() {
    }

    static String read(File audioFile) {
        if (audioFile == null || !audioFile.isFile() || audioFile.length() < 4L) {
            return "";
        }

        try (RandomAccessFile input = new RandomAccessFile(audioFile, "r")) {
            byte[] header = new byte[12];
            input.readFully(header, 0, (int) Math.min(header.length, input.length()));
            if (matches(header, 0, "ID3")) {
                return readId3UnsynchronisedLyrics(input);
            }
            if (matches(header, 0, "fLaC")) {
                return readFlacVorbisLyrics(input);
            }
            if (matches(header, 4, "ftyp")) {
                return readM4aLyrics(input);
            }
        } catch (IOException ignored) {
            // Embedded metadata is optional and must never interfere with audio playback.
        }
        return "";
    }

    private static String readId3UnsynchronisedLyrics(RandomAccessFile input) throws IOException {
        input.seek(0L);
        byte[] header = new byte[10];
        input.readFully(header);
        int version = header[3] & 0xFF;
        if (version < 3 || version > 4) {
            return "";
        }

        int tagSize = synchsafeInt(header, 6);
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES || tagSize > input.length() - 10L) {
            return "";
        }
        byte[] tag = new byte[tagSize];
        input.readFully(tag);
        if ((header[5] & 0x80) != 0) {
            tag = removeUnsynchronisation(tag);
        }

        int offset = skipId3ExtendedHeader(tag, version, header[5] & 0xFF);
        while (offset + 10 <= tag.length) {
            String id = ascii(tag, offset, 4);
            if (id.trim().isEmpty()) {
                break;
            }
            int size = version == 4 ? synchsafeInt(tag, offset + 4) : bigEndianInt(tag, offset + 4);
            int frameStart = offset + 10;
            if (size <= 0 || frameStart + size > tag.length) {
                break;
            }
            if ("USLT".equals(id)) {
                String lyrics = decodeUslt(tag, frameStart, size);
                if (!lyrics.isEmpty()) {
                    return lyrics;
                }
            }
            offset = frameStart + size;
        }
        return "";
    }

    private static int skipId3ExtendedHeader(byte[] tag, int version, int flags) {
        if ((flags & 0x40) == 0 || tag.length < 4) {
            return 0;
        }
        int size = version == 4 ? synchsafeInt(tag, 0) : bigEndianInt(tag, 0);
        if (size <= 0) {
            return 0;
        }
        int skipped = version == 3 ? size + 4 : size;
        return skipped > 0 && skipped < tag.length ? skipped : 0;
    }

    private static String decodeUslt(byte[] data, int offset, int length) {
        if (length < 5) {
            return "";
        }
        int encoding = data[offset] & 0xFF;
        int descriptionStart = offset + 4; // encoding + ISO-639 language code
        int textStart = skipEncodedTerminator(data, descriptionStart, offset + length, encoding);
        return textStart < offset + length ? decodeText(data, textStart, offset + length - textStart, encoding) : "";
    }

    private static String readFlacVorbisLyrics(RandomAccessFile input) throws IOException {
        input.seek(4L);
        boolean last = false;
        while (!last && input.getFilePointer() + 4L <= input.length()) {
            int blockHeader = input.readUnsignedByte();
            last = (blockHeader & 0x80) != 0;
            int type = blockHeader & 0x7F;
            int length = input.readUnsignedByte() << 16 | input.readUnsignedByte() << 8 | input.readUnsignedByte();
            if (length < 0 || input.getFilePointer() + length > input.length()) {
                return "";
            }
            if (type == 4 && length <= MAX_TAG_BYTES) {
                byte[] block = new byte[length];
                input.readFully(block);
                String lyrics = parseVorbisComments(block);
                if (!lyrics.isEmpty()) {
                    return lyrics;
                }
            } else {
                input.seek(input.getFilePointer() + length);
            }
        }
        return "";
    }

    private static String parseVorbisComments(byte[] block) {
        int offset = 0;
        if (block.length < 8) {
            return "";
        }
        int vendorLength = littleEndianInt(block, offset);
        offset += 4;
        if (vendorLength < 0 || offset + vendorLength + 4 > block.length) {
            return "";
        }
        offset += vendorLength;
        int count = littleEndianInt(block, offset);
        offset += 4;
        for (int index = 0; index < count && offset + 4 <= block.length; index++) {
            int length = littleEndianInt(block, offset);
            offset += 4;
            if (length < 0 || offset + length > block.length) {
                return "";
            }
            String comment = new String(block, offset, length, StandardCharsets.UTF_8);
            offset += length;
            int separator = comment.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = comment.substring(0, separator).trim().toUpperCase(Locale.ROOT);
            if ("LYRICS".equals(key) || "UNSYNCEDLYRICS".equals(key)
                    || "SYNCLYRICS".equals(key) || "LRC".equals(key) || "LYRIC".equals(key)) {
                String lyrics = clean(comment.substring(separator + 1));
                if (!lyrics.isEmpty()) {
                    return lyrics;
                }
            }
        }
        return "";
    }

    private static String readM4aLyrics(RandomAccessFile input) throws IOException {
        return scanM4aAtoms(input, 0L, input.length(), 0);
    }

    private static String scanM4aAtoms(RandomAccessFile input, long start, long end, int depth) throws IOException {
        if (depth > 6 || start < 0L || end <= start || end > input.length()) {
            return "";
        }
        long position = start;
        while (position + 8L <= end) {
            input.seek(position);
            long size = input.readInt() & 0xFFFFFFFFL;
            String type = readAtomType(input);
            long headerSize = 8L;
            if (size == 1L) {
                if (position + 16L > end) {
                    return "";
                }
                size = input.readLong();
                headerSize = 16L;
            } else if (size == 0L) {
                size = end - position;
            }
            if (size < headerSize || position + size > end) {
                return "";
            }

            long contentStart = position + headerSize;
            long atomEnd = position + size;
            if (MP4_LYRICS_ATOM.equals(type)) {
                String lyrics = readM4aLyricsAtom(input, contentStart, atomEnd);
                if (!lyrics.isEmpty()) {
                    return lyrics;
                }
            } else if (isM4aContainer(type)) {
                long childStart = "meta".equals(type) ? Math.min(atomEnd, contentStart + 4L) : contentStart;
                String lyrics = scanM4aAtoms(input, childStart, atomEnd, depth + 1);
                if (!lyrics.isEmpty()) {
                    return lyrics;
                }
            }
            position = atomEnd;
        }
        return "";
    }

    private static String readM4aLyricsAtom(RandomAccessFile input, long start, long end) throws IOException {
        long position = start;
        while (position + 8L <= end) {
            input.seek(position);
            long size = input.readInt() & 0xFFFFFFFFL;
            String type = readAtomType(input);
            if (size < 8L || position + size > end) {
                return "";
            }
            if ("data".equals(type) && size > 16L) {
                long textStart = position + 16L; // atom header + type/locale fields
                int textLength = (int) Math.min(MAX_TAG_BYTES, position + size - textStart);
                byte[] text = new byte[textLength];
                input.seek(textStart);
                input.readFully(text);
                return decodeM4aText(text);
            }
            position += size;
        }
        return "";
    }

    private static boolean isM4aContainer(String type) {
        return "moov".equals(type) || "udta".equals(type) || "meta".equals(type) || "ilst".equals(type);
    }

    private static String decodeM4aText(byte[] text) {
        if (text.length == 0) {
            return "";
        }
        if (text.length >= 2 && ((text[0] & 0xFF) == 0xFE && (text[1] & 0xFF) == 0xFF
                || (text[0] & 0xFF) == 0xFF && (text[1] & 0xFF) == 0xFE)) {
            return clean(new String(text, StandardCharsets.UTF_16));
        }
        return clean(new String(text, StandardCharsets.UTF_8));
    }

    private static String decodeText(byte[] data, int offset, int length, int encoding) {
        if (length <= 0 || offset < 0 || offset + length > data.length) {
            return "";
        }
        Charset charset;
        switch (encoding) {
            case 1:
                charset = StandardCharsets.UTF_16;
                break;
            case 2:
                charset = StandardCharsets.UTF_16BE;
                break;
            case 3:
                charset = StandardCharsets.UTF_8;
                break;
            default:
                charset = StandardCharsets.ISO_8859_1;
                break;
        }
        return clean(new String(data, offset, length, charset));
    }

    private static int skipEncodedTerminator(byte[] data, int start, int end, int encoding) {
        if (encoding == 1 || encoding == 2) {
            for (int index = start; index + 1 < end; index += 2) {
                if (data[index] == 0 && data[index + 1] == 0) {
                    return index + 2;
                }
            }
            return end;
        }
        for (int index = start; index < end; index++) {
            if (data[index] == 0) {
                return index + 1;
            }
        }
        return end;
    }

    private static byte[] removeUnsynchronisation(byte[] source) {
        byte[] cleaned = new byte[source.length];
        int write = 0;
        for (int read = 0; read < source.length; read++) {
            cleaned[write++] = source[read];
            if ((source[read] & 0xFF) == 0xFF && read + 1 < source.length && source[read + 1] == 0) {
                read++;
            }
        }
        byte[] result = new byte[write];
        System.arraycopy(cleaned, 0, result, 0, write);
        return result;
    }

    private static int synchsafeInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            return -1;
        }
        return (bytes[offset] & 0x7F) << 21 | (bytes[offset + 1] & 0x7F) << 14
                | (bytes[offset + 2] & 0x7F) << 7 | bytes[offset + 3] & 0x7F;
    }

    private static int bigEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            return -1;
        }
        return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8 | bytes[offset + 3] & 0xFF;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            return -1;
        }
        return bytes[offset] & 0xFF | (bytes[offset + 1] & 0xFF) << 8
                | (bytes[offset + 2] & 0xFF) << 16 | (bytes[offset + 3] & 0xFF) << 24;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
    }

    private static String readAtomType(RandomAccessFile input) throws IOException {
        byte[] type = new byte[4];
        input.readFully(type);
        return new String(type, StandardCharsets.ISO_8859_1);
    }

    private static boolean matches(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if ((bytes[offset + index] & 0xFF) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').replace("\r\n", "\n").trim();
    }
}
