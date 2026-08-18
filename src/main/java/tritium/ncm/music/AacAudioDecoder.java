package tritium.ncm.music;

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
import java.util.List;

/**
 * Decodes the AAC streams that are commonly returned as ADTS (.aac) or
 * ISO-BMFF/M4A (.m4a) into a standard 16-bit PCM WAV cache. Generic MP4
 * payloads are intentionally excluded: they can be video, DRM-protected or
 * use an unsupported audio codec, and are rejected by the playback pipeline
 * before this decoder is invoked.
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
        if (!"aac".equals(container) && !"m4a".equals(container)) {
            throw new IOException("Unsupported AAC container: " + container);
        }

        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Unable to replace incomplete AAC decode cache: " + temporary.getName());
        }

        boolean completed = false;
        reportProgress(progressListener, 0.0);
        try (PcmWaveWriter writer = new PcmWaveWriter(temporary)) {
            if ("aac".equals(container)) {
                decodeAdts(input, writer, progressListener);
            } else {
                decodeIsoBaseMedia(input, writer, progressListener);
            }
            writer.finish();
            reportProgress(progressListener, 1.0);
            completed = true;
        } catch (AACException e) {
            throw new IOException("AAC decoder rejected the audio stream", e);
        } finally {
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
            while (track.hasMoreFrames()) {
                Frame frame = track.readNextFrame();
                if (frame == null || frame.getData() == null || frame.getData().length == 0) {
                    throw new IOException("ISO-BMFF AAC track contains an invalid audio frame");
                }
                decoder.decodeFrame(frame.getData(), buffer);
                writer.write(buffer);
                long endOffset = frame.getOffset() + frame.getSize();
                reportProgress(progressListener, input.length() <= 0L
                        ? 0.0 : (double) endOffset / (double) input.length());
            }
        }
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
