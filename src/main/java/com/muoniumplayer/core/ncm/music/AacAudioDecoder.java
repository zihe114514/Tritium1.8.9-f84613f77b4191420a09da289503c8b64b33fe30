package com.muoniumplayer.core.ncm.music;

import repackage.net.sourceforge.jaad.aac.AACException;
import repackage.net.sourceforge.jaad.aac.Decoder;
import repackage.net.sourceforge.jaad.aac.SampleBuffer;
import repackage.net.sourceforge.jaad.adts.ADTSDemultiplexer;
import repackage.net.sourceforge.jaad.mp4.MP4Container;
import repackage.net.sourceforge.jaad.mp4.api.AudioTrack;
import repackage.net.sourceforge.jaad.mp4.api.Frame;
import repackage.net.sourceforge.jaad.mp4.api.Movie;
import repackage.net.sourceforge.jaad.mp4.api.Track;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the AAC streams that are commonly returned as ADTS (.aac) or
 * ISO-BMFF/M4A (.m4a) and audio-bearing MP4/DASH (.m4s) payloads into a standard
 * 16-bit PCM WAV cache. Files without a decodable AAC audio track fail closed.
 */
final class AacAudioDecoder {
    private static final int WAV_HEADER_SIZE = 44;

    private AacAudioDecoder() {
    }

    interface ProgressListener {
        void onProgress(double progress);
    }

    static File decodeToWav(File input, String container, File destination) throws IOException {
        return decodeToWav(input, container, destination, null);
    }

    static File decodeToWav(File input, String container, File destination,
                            ProgressListener progressListener) throws IOException {
        if (input == null || !input.isFile()) {
            throw new IOException("AAC input file does not exist");
        }
        if (!"aac".equals(container) && !"m4a".equals(container) && !"mp4".equals(container)) {
            throw new IOException("Unsupported AAC container: " + container);
        }

        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Unable to replace incomplete AAC decode cache: " + temporary.getName());
        }

        boolean completed = false;
        File decoderInput = input;
        reportProgress(progressListener, 0.0);
        try (PcmWaveWriter writer = new PcmWaveWriter(temporary)) {
            if ("aac".equals(container)) {
                decodeAdts(input, writer, progressListener);
            } else {
                // Some JOOX M4A files contain a legacy QuickTime "tags" atom. JAAD's MP4
                // demuxer miscalculates that atom's consumed length and seeks indefinitely.
                // Re-labeling only this metadata atom as a same-sized free atom preserves every
                // audio byte and every sample offset, while letting the embedded AAC decoder
                // process the stream normally.
                decoderInput = sanitizeJaadIncompatibleMetadata(input, destination);
                decodeIsoBaseMedia(decoderInput, writer, progressListener);
            }
            writer.finish();
            reportProgress(progressListener, 1.0);
            completed = true;
        } catch (AACException e) {
            throw new IOException("AAC decoder rejected the audio stream", e);
        } finally {
            if (!decoderInput.equals(input) && decoderInput.exists() && !decoderInput.delete()) {
                System.err.println("[Music] Unable to remove temporary sanitized AAC input: " + decoderInput.getName());
            }
            if (!completed && temporary.exists()) {
                temporary.delete();
            }
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Unable to replace decoded AAC cache: " + destination.getName());
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Unable to finalize decoded AAC cache: " + destination.getName());
        }
        return destination;
    }

    private static void decodeAdts(File input, PcmWaveWriter writer,
                                   ProgressListener progressListener) throws IOException, AACException {
        try (ProgressInputStream raw = new ProgressInputStream(new FileInputStream(input), input.length(), progressListener);
             BufferedInputStream stream = new BufferedInputStream(raw)) {
            ADTSDemultiplexer demultiplexer = new ADTSDemultiplexer(stream);
            Decoder decoder = new Decoder(demultiplexer.getDecoderSpecificInfo());
            SampleBuffer buffer = new SampleBuffer();
            while (true) {
                try {
                    decoder.decodeFrame(demultiplexer.readNextFrame(), buffer);
                } catch (EOFException endOfStream) {
                    break;
                }
                writer.write(buffer);
                reportProgress(progressListener, raw.getProgress());
            }
        }
    }

    /**
     * Transcodes the first decodable AAC audio track from an M4A file.
     * DRM-protected and non-AAC files fail closed so the caller can request a standard MP3 fallback.
     */
    private static void decodeIsoBaseMedia(File input, PcmWaveWriter writer,
                                           ProgressListener progressListener) throws IOException, AACException {
        try (RandomAccessFile file = new RandomAccessFile(input, "r")) {
            MP4Container container = new MP4Container(file);
            Movie movie = container.getMovie();
            List<Track> tracks = movie == null ? null : movie.getTracks(AudioTrack.AudioCodec.AAC);
            if (tracks == null || tracks.isEmpty() || !(tracks.get(0) instanceof AudioTrack)) {
                throw new IOException("ISO-BMFF file does not contain a supported AAC audio track");
            }

            AudioTrack track = (AudioTrack) tracks.get(0);
            Decoder decoder = new Decoder(track.getDecoderSpecificInfo());
            SampleBuffer buffer = new SampleBuffer();
            boolean decodedFromSampleTable = false;
            while (track.hasMoreFrames()) {
                Frame frame = track.readNextFrame();
                if (frame == null || frame.getData() == null || frame.getData().length == 0) {
                    throw new IOException("ISO-BMFF AAC track contains an invalid audio frame");
                }
                decoder.decodeFrame(frame.getData(), buffer);
                writer.write(buffer);
                decodedFromSampleTable = true;
                long endOffset = frame.getOffset() + frame.getSize();
                reportProgress(progressListener, input.length() <= 0L
                        ? 0.0 : (double) endOffset / (double) input.length());
            }

            // Bilibili DASH audio is a fragmented MP4 (.m4s): the initial moov atom has an
            // empty sample table, while the AAC frames are described by moof/trun fragments.
            // JAAD can read the AAC decoder configuration from moov, but not those fragments.
            if (!decodedFromSampleTable) {
                decodeFragmentedMp4Aac(file, decoder, writer, progressListener);
            }
        }
    }

    /**
     * Decodes unencrypted fragmented MP4 AAC streams such as Bilibili's audio-only DASH .m4s
     * payloads. We intentionally support the common tfhd/trun subset emitted by Bilibili and
     * fail closed for malformed/encrypted fragments instead of presenting a stalled transcode.
     */
    private static void decodeFragmentedMp4Aac(RandomAccessFile file, Decoder decoder, PcmWaveWriter writer,
                                               ProgressListener progressListener) throws IOException, AACException {
        long length = file.length();
        long offset = 0L;
        int decodedFrames = 0;
        while (offset + 8L <= length) {
            Mp4Box box = readMp4Box(file, offset, length);
            if (box == null) break;
            if ("moof".equals(box.type)) {
                Mp4Box mediaData = findFollowingMediaDataBox(file, box.end, length);
                if (mediaData == null) {
                    throw new IOException("Fragmented MP4 moof has no following mdat payload");
                }
                decodedFrames += decodeMovieFragment(file, box, mediaData, decoder, writer, progressListener, length);
            }
            offset = box.end;
        }
        if (decodedFrames <= 0) {
            throw new IOException("ISO-BMFF file has neither sample-table nor DASH AAC frames");
        }
    }

    private static Mp4Box findFollowingMediaDataBox(RandomAccessFile file, long offset, long length)
            throws IOException {
        long cursor = offset;
        while (cursor + 8L <= length) {
            Mp4Box box = readMp4Box(file, cursor, length);
            if (box == null) return null;
            if ("mdat".equals(box.type)) return box;
            // A following fragment begins before media data: do not accidentally read it as samples.
            if ("moof".equals(box.type)) return null;
            cursor = box.end;
        }
        return null;
    }

    private static int decodeMovieFragment(RandomAccessFile file, Mp4Box movieFragment, Mp4Box mediaData,
                                           Decoder decoder, PcmWaveWriter writer,
                                           ProgressListener progressListener, long fileLength)
            throws IOException, AACException {
        int decodedFrames = 0;
        long cursor = movieFragment.dataStart;
        while (cursor + 8L <= movieFragment.end) {
            Mp4Box child = readMp4Box(file, cursor, movieFragment.end);
            if (child == null) break;
            if ("traf".equals(child.type)) {
                decodedFrames += decodeTrackFragment(file, child, movieFragment, mediaData, decoder, writer,
                        progressListener, fileLength);
            }
            cursor = child.end;
        }
        return decodedFrames;
    }

    private static int decodeTrackFragment(RandomAccessFile file, Mp4Box trackFragment, Mp4Box movieFragment,
                                           Mp4Box mediaData, Decoder decoder, PcmWaveWriter writer,
                                           ProgressListener progressListener, long fileLength)
            throws IOException, AACException {
        int defaultSampleSize = -1;
        boolean defaultBaseIsMoof = false;
        long nextSampleOffset = mediaData.dataStart;
        int decodedFrames = 0;

        long cursor = trackFragment.dataStart;
        while (cursor + 8L <= trackFragment.end) {
            Mp4Box child = readMp4Box(file, cursor, trackFragment.end);
            if (child == null) break;
            if ("tfhd".equals(child.type)) {
                TfhdDefaults defaults = readTfhdDefaults(file, child);
                defaultSampleSize = defaults.defaultSampleSize;
                defaultBaseIsMoof = defaults.defaultBaseIsMoof;
            } else if ("trun".equals(child.type)) {
                TrunResult result = decodeTrackRun(file, child, movieFragment, mediaData, defaultSampleSize,
                        defaultBaseIsMoof, nextSampleOffset, decoder, writer, progressListener, fileLength);
                nextSampleOffset = result.nextSampleOffset;
                decodedFrames += result.decodedFrames;
            }
            cursor = child.end;
        }
        return decodedFrames;
    }

    private static TfhdDefaults readTfhdDefaults(RandomAccessFile file, Mp4Box box) throws IOException {
        file.seek(box.dataStart);
        int flags = readFullBoxFlags(file, box);
        requireRemaining(file, box.end, 4L, "tfhd track id");
        readUnsignedInt(file); // track_ID; a Bilibili audio .m4s contains a single audio traf.
        if ((flags & 0x000001) != 0) {
            requireRemaining(file, box.end, 8L, "tfhd base data offset");
            file.readLong();
        }
        if ((flags & 0x000002) != 0) {
            requireRemaining(file, box.end, 4L, "tfhd sample description index");
            readUnsignedInt(file);
        }
        if ((flags & 0x000008) != 0) {
            requireRemaining(file, box.end, 4L, "tfhd default sample duration");
            readUnsignedInt(file);
        }
        int defaultSampleSize = -1;
        if ((flags & 0x000010) != 0) {
            requireRemaining(file, box.end, 4L, "tfhd default sample size");
            long size = readUnsignedInt(file);
            defaultSampleSize = size > Integer.MAX_VALUE ? -1 : (int) size;
        }
        return new TfhdDefaults(defaultSampleSize, (flags & 0x020000) != 0);
    }

    private static TrunResult decodeTrackRun(RandomAccessFile file, Mp4Box box, Mp4Box movieFragment,
                                              Mp4Box mediaData, int defaultSampleSize, boolean defaultBaseIsMoof,
                                              long fallbackSampleOffset, Decoder decoder, PcmWaveWriter writer,
                                              ProgressListener progressListener, long fileLength)
            throws IOException, AACException {
        file.seek(box.dataStart);
        int flags = readFullBoxFlags(file, box);
        requireRemaining(file, box.end, 4L, "trun sample count");
        long rawSampleCount = readUnsignedInt(file);
        if (rawSampleCount <= 0L || rawSampleCount > 200_000L) {
            throw new IOException("Fragmented MP4 has an invalid trun sample count");
        }
        int sampleCount = (int) rawSampleCount;

        long sampleOffset = fallbackSampleOffset;
        if ((flags & 0x000001) != 0) {
            requireRemaining(file, box.end, 4L, "trun data offset");
            int relativeOffset = file.readInt();
            long baseOffset = defaultBaseIsMoof ? movieFragment.start : movieFragment.start;
            sampleOffset = baseOffset + relativeOffset;
        }
        if ((flags & 0x000004) != 0) {
            requireRemaining(file, box.end, 4L, "trun first sample flags");
            readUnsignedInt(file);
        }

        boolean hasSampleDuration = (flags & 0x000100) != 0;
        boolean hasSampleSize = (flags & 0x000200) != 0;
        boolean hasSampleFlags = (flags & 0x000400) != 0;
        boolean hasCompositionOffset = (flags & 0x000800) != 0;
        List<Integer> sampleSizes = new ArrayList<>(sampleCount);
        long totalSampleBytes = 0L;

        // Read the complete trun table before seeking into mdat. Mixing table parsing with frame
        // reads moves RandomAccessFile away from trun and was the cause of truncated-table errors.
        for (int index = 0; index < sampleCount; index++) {
            if (hasSampleDuration) {
                requireRemaining(file, box.end, 4L, "trun sample duration");
                readUnsignedInt(file);
            }
            int sampleSize = defaultSampleSize;
            if (hasSampleSize) {
                requireRemaining(file, box.end, 4L, "trun sample size");
                long size = readUnsignedInt(file);
                sampleSize = size > Integer.MAX_VALUE ? -1 : (int) size;
            }
            if (hasSampleFlags) {
                requireRemaining(file, box.end, 4L, "trun sample flags");
                readUnsignedInt(file);
            }
            if (hasCompositionOffset) {
                requireRemaining(file, box.end, 4L, "trun composition offset");
                readUnsignedInt(file);
            }
            if (sampleSize <= 0 || totalSampleBytes > Integer.MAX_VALUE - (long) sampleSize) {
                throw new IOException("Fragmented MP4 AAC sample has an invalid size");
            }
            sampleSizes.add(sampleSize);
            totalSampleBytes += sampleSize;
        }
        if (sampleOffset < mediaData.dataStart || sampleOffset > mediaData.end - totalSampleBytes) {
            throw new IOException("Fragmented MP4 AAC samples are outside the mdat payload");
        }

        SampleBuffer buffer = new SampleBuffer();
        for (Integer sampleSize : sampleSizes) {
            byte[] frame = new byte[sampleSize];
            file.seek(sampleOffset);
            file.readFully(frame);
            decoder.decodeFrame(frame, buffer);
            writer.write(buffer);
            sampleOffset += sampleSize;
            reportProgress(progressListener, fileLength <= 0L ? 0.0 : (double) sampleOffset / (double) fileLength);
        }
        return new TrunResult(sampleOffset, sampleCount);
    }
    private static int readFullBoxFlags(RandomAccessFile file, Mp4Box box) throws IOException {
        requireRemaining(file, box.end, 4L, "full box header");
        file.readUnsignedByte(); // version
        return (file.readUnsignedByte() << 16) | (file.readUnsignedByte() << 8) | file.readUnsignedByte();
    }

    private static void requireRemaining(RandomAccessFile file, long end, long amount, String field) throws IOException {
        if (amount < 0L || file.getFilePointer() > end - amount) {
            throw new IOException("Truncated fragmented MP4 " + field);
        }
    }

    private static Mp4Box readMp4Box(RandomAccessFile file, long start, long boundary) throws IOException {
        if (start < 0L || start + 8L > boundary) return null;
        file.seek(start);
        long size = readUnsignedInt(file);
        String type = readBoxType(file);
        long headerSize = 8L;
        if (size == 1L) {
            if (start + 16L > boundary) return null;
            size = file.readLong();
            headerSize = 16L;
        } else if (size == 0L) {
            size = boundary - start;
        }
        if (size < headerSize || size > boundary - start) return null;
        return new Mp4Box(start, start + headerSize, start + size, type);
    }

    private static final class Mp4Box {
        private final long start;
        private final long dataStart;
        private final long end;
        private final String type;

        private Mp4Box(long start, long dataStart, long end, String type) {
            this.start = start;
            this.dataStart = dataStart;
            this.end = end;
            this.type = type;
        }
    }

    private static final class TfhdDefaults {
        private final int defaultSampleSize;
        private final boolean defaultBaseIsMoof;

        private TfhdDefaults(int defaultSampleSize, boolean defaultBaseIsMoof) {
            this.defaultSampleSize = defaultSampleSize;
            this.defaultBaseIsMoof = defaultBaseIsMoof;
        }
    }

    private static final class TrunResult {
        private final long nextSampleOffset;
        private final int decodedFrames;

        private TrunResult(long nextSampleOffset, int decodedFrames) {
            this.nextSampleOffset = nextSampleOffset;
            this.decodedFrames = decodedFrames;
        }
    }

    /**
     * Produces a temporary MP4/M4A copy only when the file contains JAAD's problematic legacy
     * {@code udta/tags} metadata atom. The atom is metadata-only, so replacing its type with
     * {@code free} leaves the ISO-BMFF layout, media data and all chunk offsets unchanged.
     */
    private static File sanitizeJaadIncompatibleMetadata(File input, File destination) throws IOException {
        List<Long> tagsBoxes = new ArrayList<>();
        try (RandomAccessFile source = new RandomAccessFile(input, "r")) {
            collectLegacyTagsBoxes(source, 0L, source.length(), false, 0, tagsBoxes);
        }
        if (tagsBoxes.isEmpty()) {
            return input;
        }

        File sanitized = new File(destination.getParentFile(), destination.getName() + ".jaad-input");
        Files.copy(input.toPath(), sanitized.toPath(), StandardCopyOption.REPLACE_EXISTING);
        boolean completed = false;
        try (RandomAccessFile output = new RandomAccessFile(sanitized, "rw")) {
            for (Long boxOffset : tagsBoxes) {
                output.seek(boxOffset + 4L);
                output.write(new byte[]{'f', 'r', 'e', 'e'});
            }
            completed = true;
            return sanitized;
        } finally {
            if (!completed && sanitized.exists()) {
                sanitized.delete();
            }
        }
    }

    private static void collectLegacyTagsBoxes(RandomAccessFile file, long start, long end,
                                               boolean insideUserData, int depth,
                                               List<Long> tagsBoxes) throws IOException {
        if (depth > 16 || start < 0L || end < start || end > file.length()) {
            return;
        }
        long offset = start;
        while (offset + 8L <= end) {
            file.seek(offset);
            long size = readUnsignedInt(file);
            String type = readBoxType(file);
            long headerSize = 8L;
            if (size == 1L) {
                if (offset + 16L > end) return;
                size = file.readLong();
                headerSize = 16L;
            } else if (size == 0L) {
                size = end - offset;
            }
            if (size < headerSize || size > end - offset) {
                return;
            }

            if (insideUserData && "tags".equals(type)) {
                tagsBoxes.add(offset);
            } else if (isMp4ContainerBox(type)) {
                collectLegacyTagsBoxes(file, offset + headerSize, offset + size,
                        insideUserData || "udta".equals(type), depth + 1, tagsBoxes);
            }
            offset += size;
        }
    }

    private static long readUnsignedInt(RandomAccessFile file) throws IOException {
        return ((long) file.readUnsignedByte() << 24)
                | ((long) file.readUnsignedByte() << 16)
                | ((long) file.readUnsignedByte() << 8)
                | (long) file.readUnsignedByte();
    }

    private static String readBoxType(RandomAccessFile file) throws IOException {
        byte[] value = new byte[4];
        file.readFully(value);
        return new String(value, "ISO-8859-1");
    }

    private static boolean isMp4ContainerBox(String type) {
        return "moov".equals(type) || "trak".equals(type) || "mdia".equals(type)
                || "minf".equals(type) || "stbl".equals(type) || "edts".equals(type)
                || "dinf".equals(type) || "udta".equals(type);
    }

    private static void reportProgress(ProgressListener progressListener, double progress) {
        if (progressListener != null) {
            progressListener.onProgress(Math.max(0.0, Math.min(1.0, progress)));
        }
    }

    private static final class ProgressInputStream extends FilterInputStream {
        private final long totalBytes;
        private final ProgressListener listener;
        private long readBytes;
        private long lastReportedBytes = -1L;

        private ProgressInputStream(FileInputStream input, long totalBytes, ProgressListener listener) {
            super(input);
            this.totalBytes = totalBytes;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                readBytes++;
                reportIfNeeded();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                readBytes += count;
                reportIfNeeded();
            }
            return count;
        }

        private void reportIfNeeded() {
            if (listener == null || totalBytes <= 0L) return;
            if (readBytes == totalBytes || readBytes - lastReportedBytes >= Math.max(4096L, totalBytes / 100L)) {
                lastReportedBytes = readBytes;
                reportProgress(listener, (double) readBytes / (double) totalBytes);
            }
        }

        private double getProgress() {
            return totalBytes <= 0L ? 0.0 : (double) readBytes / (double) totalBytes;
        }
    }
    private static final class PcmWaveWriter implements AutoCloseable {
        private final RandomAccessFile output;
        private int sampleRate = -1;
        private int channels = -1;
        private int bitsPerSample = -1;
        private long dataBytes;
        private boolean finished;

        private PcmWaveWriter(File outputFile) throws IOException {
            output = new RandomAccessFile(outputFile, "rw");
            output.setLength(0L);
            output.write(new byte[WAV_HEADER_SIZE]);
        }

        private void write(SampleBuffer buffer) throws IOException {
            if (buffer == null || buffer.getData() == null || buffer.getData().length == 0) {
                return;
            }
            int frameSampleRate = buffer.getSampleRate();
            int frameChannels = buffer.getChannels();
            int frameBits = buffer.getBitsPerSample();
            if (frameSampleRate <= 0 || frameChannels <= 0 || frameChannels > 2 || frameBits != 16) {
                throw new IOException("Unsupported decoded AAC PCM format: " + frameSampleRate + "Hz, "
                        + frameChannels + " channels, " + frameBits + " bits");
            }
            if (sampleRate < 0) {
                sampleRate = frameSampleRate;
                channels = frameChannels;
                bitsPerSample = frameBits;
            } else if (sampleRate != frameSampleRate || channels != frameChannels || bitsPerSample != frameBits) {
                throw new IOException("AAC stream changed PCM format while decoding");
            }

            byte[] data = buffer.getData();
            if (data.length % 2 != 0) {
                throw new IOException("Decoded AAC frame has an invalid PCM byte count");
            }
            if (buffer.isBigEndian()) {
                for (int index = 0; index < data.length; index += 2) {
                    byte first = data[index];
                    data[index] = data[index + 1];
                    data[index + 1] = first;
                }
            }
            if (dataBytes + data.length > 0xFFFFFFFFL) {
                throw new IOException("Decoded AAC WAV exceeds the 4 GiB RIFF limit");
            }
            // A third-party link can point at an hours-long upload. Abort while decoding instead of
            // filling the cache and then failing the player's single large heap allocation.
            long pcmLimit = PlaybackMemoryLimits.maxDecodedPcmBytes();
            if (dataBytes + data.length > pcmLimit) {
                throw new IOException("解码中止：" + PlaybackMemoryLimits.describeOverLimit(dataBytes)
                        + "（避免游戏内存溢出）");
            }
            output.write(data);
            dataBytes += data.length;
        }

        private void finish() throws IOException {
            if (finished) {
                return;
            }
            if (sampleRate <= 0 || channels <= 0 || dataBytes <= 0) {
                throw new IOException("AAC stream did not decode to PCM audio");
            }

            int blockAlign = channels * (bitsPerSample / 8);
            long byteRate = (long) sampleRate * blockAlign;
            if (byteRate > Integer.MAX_VALUE) {
                throw new IOException("Decoded AAC byte rate is out of WAV range");
            }

            output.seek(0L);
            writeAscii("RIFF");
            writeLittleEndianInt(36L + dataBytes);
            writeAscii("WAVE");
            writeAscii("fmt ");
            writeLittleEndianInt(16L);
            writeLittleEndianShort(1);
            writeLittleEndianShort(channels);
            writeLittleEndianInt(sampleRate);
            writeLittleEndianInt(byteRate);
            writeLittleEndianShort(blockAlign);
            writeLittleEndianShort(bitsPerSample);
            writeAscii("data");
            writeLittleEndianInt(dataBytes);
            finished = true;
        }

        private void writeAscii(String value) throws IOException {
            output.write(value.getBytes("US-ASCII"));
        }

        private void writeLittleEndianShort(int value) throws IOException {
            output.write(value & 0xFF);
            output.write((value >>> 8) & 0xFF);
        }

        private void writeLittleEndianInt(long value) throws IOException {
            output.write((int) (value & 0xFF));
            output.write((int) ((value >>> 8) & 0xFF));
            output.write((int) ((value >>> 16) & 0xFF));
            output.write((int) ((value >>> 24) & 0xFF));
        }

        @Override
        public void close() throws IOException {
            output.close();
        }
    }
}
