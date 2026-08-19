package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.muoniumplayer.core.ncm.DeviceIdGenerator;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.json.JsonUtils;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Uploads real NetEase playback events through the same NCBL client-log transport
 * used by the desktop client. This replaces the incompatible direct weapi report
 * endpoint for this desktop-player feature.
 *
 * <p>The caller supplies only a session that has passed the local real-playback
 * threshold. This class never fabricates duration, source, or account data.</p>
 */
final class NeteaseClientLogUploader {

    private static final String UPLOAD_URL = "https://clientlog3.music.163.com/api/clientlog/encrypt/upload?multiupload=true";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final byte[] MAGIC = new byte[]{'N', 'C', 'B', 'L'};
    private static final int HEADER_FIXED_LENGTH = 70;
    private static final int META_BLOCK_TYPE = 0x4343;
    private static final int MAX_FRAME_LENGTH = 0x8000;

    /* Values and byte ordering are from the provided desktop-client API sample. */
    private static final BigInteger RSA_N = new BigInteger(
            "fd90bd466ff9bc8a3fec2fbcf263b90d5c564879fa5d7aab89b31c1d5cb4139d", 16);
    private static final BigInteger RSA_E = BigInteger.valueOf(65537L);

    private static final String APP_VERSION = "3.1.35";
    private static final String APP_VERSION_CODE = "205293";
    private static final String APP_VERSION_FULL = APP_VERSION + "." + APP_VERSION_CODE;
    private static final String CHANNEL = "netease";
    private static final String SYSTEM_VERSION = "Microsoft-Windows-11-Professional-build-26100-64bit";
    private static final String CLIENT_ID = "muonium-player";

    private static volatile String deviceId;
    private static volatile ZstdNative zstd;

    private NeteaseClientLogUploader() {
    }

    /** Sends the recent-play event immediately after local audio starts. */
    static UploadResult uploadPlayView(Music song, PlayList source) {
        return upload(song, source, 0, null, true);
    }

    /** Sends a cumulative real-play duration checkpoint. */
    static UploadResult uploadPlayDuration(Music song, PlayList source, int playedSeconds,
                                           NeteasePlaybackHistoryReporter.EndReason endReason) {
        return upload(song, source, playedSeconds, endReason, false);
    }

    private static UploadResult upload(Music song, PlayList source, int playedSeconds,
                                       NeteasePlaybackHistoryReporter.EndReason endReason,
                                       boolean playViewOnly) {
        long startedAt = System.currentTimeMillis();
        if (song == null || !song.isNetease() || song.getId() <= 0L
                || (!playViewOnly && playedSeconds <= 0)) {
            return UploadResult.failure(0L, "无效的歌曲或播放时长");
        }

        String cookie = OptionsUtil.getCookie();
        if (cookie == null || cookie.trim().isEmpty()) {
            return UploadResult.failure(0L, "网易云 Cookie 不存在");
        }

        try {
            long now = System.currentTimeMillis();
            long eventTimeSeconds = now / 1000L;
            String sourceId = resolveSourceId(source);
            String sourceType = sourceId.isEmpty() ? "" : "list";
            String sourceName = source == null ? "" : safe(source.getName());
            int resourceSeconds = toResourceSeconds(song);

            List<LogRecord> records = new ArrayList<LogRecord>(1);
            if (playViewOnly) {
                records.add(new LogRecord(eventTimeSeconds, "_plv",
                        buildPlayView(now, song, sourceId, sourceType, sourceName, resourceSeconds)));
            } else {
                records.add(new LogRecord(eventTimeSeconds, "_pld",
                        buildPlayDuration(now, song, sourceId, sourceType, sourceName, resourceSeconds,
                                playedSeconds, endReason)));
            }

            byte[] payload = encrypt(buildMetadata(cookie), buildRecords(records));
            UploadResult result = uploadPayload(cookie, payload);
            return result.withElapsed(System.currentTimeMillis() - startedAt);
        } catch (Throwable failure) {
            String message = messageOf(failure);
            System.err.println("[NCM] Client-log " + (playViewOnly ? "recent-play" : "duration")
                    + " upload failed for " + song.getId() + ": " + message);
            return UploadResult.failure(System.currentTimeMillis() - startedAt, message);
        }
    }

    private static JsonObject buildPlayView(long now, Music song, String sourceId, String sourceType,
                                            String sourceName, int resourceSeconds) {
        JsonObject data = buildCommonData(song, sourceId, sourceType, sourceName, resourceSeconds);
        data.addProperty("app_mode", 2);
        data.addProperty("_addrefer", "[F:63][" + now + "#933#" + APP_VERSION + "#" + APP_VERSION_CODE
                + "#c9156c3][e][2][23][cell_pc_songlist_song:2|page_pc_songlist_songflow]"
                + "[" + song.getId() + ":song:x:x|:::|" + sourceId + ":list::]");
        JsonArray references = new JsonArray();
        references.add("[F:26][s][18][_ai]");
        references.add("[F:26][s][12][_ai]");
        references.add("[F:26][s][5][_ai]");
        data.add("_multirefers", references);
        return data;
    }

    private static JsonObject buildPlayDuration(long now, Music song, String sourceId, String sourceType,
                                                String sourceName, int resourceSeconds, int playedSeconds,
                                                NeteasePlaybackHistoryReporter.EndReason endReason) {
        JsonObject data = buildCommonData(song, sourceId, sourceType, sourceName, resourceSeconds);
        data.addProperty("time", playedSeconds);
        data.addProperty("realtime", playedSeconds);
        data.addProperty("app_mode", 1);
        data.addProperty("musiceffect_id", "1001");
        data.addProperty("lyriceffect", "default");
        data.addProperty("displayMode", "classic");
        data.addProperty("end", endReason == NeteasePlaybackHistoryReporter.EndReason.COMPLETED
                ? "complete" : "interrupt");
        data.addProperty("_addrefer", "[F:63][" + now + "#616#" + APP_VERSION + "#" + APP_VERSION_CODE
                + "#c9156c3][e][2][92][btn_pc_cover_play|cell_pc_songlist_song:6|page_pc_songlist_songflow]"
                + "[:::|" + song.getId() + ":song:x:x|:::|" + sourceId + ":list::]");
        JsonArray references = new JsonArray();
        references.add("[F:26][s][87][_ai]");
        references.add("[F:26][s][81][_ai]");
        references.add("[F:26][s][75][_ai]");
        data.add("_multirefers", references);
        return data;
    }

    private static JsonObject buildCommonData(Music song, String sourceId, String sourceType,
                                              String sourceName, int resourceSeconds) {
        JsonObject data = new JsonObject();
        data.addProperty("mode", "circulation");
        data.addProperty("download", 0);
        data.addProperty("alg", "");
        data.addProperty("status", "front");
        data.addProperty("id", String.valueOf(song.getId()));
        data.addProperty("bitrate", 256);
        data.addProperty("type", "song");
        data.addProperty("is_listentogether", 0);
        data.addProperty("source", sourceName);
        data.addProperty("is_heart", 0);
        data.addProperty("resource_ratio", "");
        data.addProperty("resource_time", resourceSeconds);
        data.addProperty("bitrate_level", "exhigh");
        data.addProperty("vipType", resolveVipType());
        data.addProperty("fee", song.getFee());
        data.addProperty("file", 4);
        data.addProperty("rightSource", 0);
        data.addProperty("sourceId", sourceId);
        data.addProperty("sourcetype", sourceType);
        data.addProperty("libra_abt", "");
        data.addProperty("channel", CHANNEL);
        data.addProperty("curStartChannel", "");
        return data;
    }

    private static String buildMetadata(String cookie) {
        Map<String, String> cookies = parseCookies(cookie);
        JsonObject meta = new JsonObject();
        putIfPresent(meta, "MUSIC_U", cookies.get("MUSIC_U"));
        putIfPresent(meta, "__csrf", cookies.get("__csrf"));
        putIfPresent(meta, "JSESSIONID-WYYY", cookies.get("JSESSIONID-WYYY"));
        putIfPresent(meta, "NMTID", cookies.get("NMTID"));
        putIfPresent(meta, "_ntes_nnid", cookies.get("_ntes_nnid"));
        putIfPresent(meta, "_ntes_nuid", cookies.get("_ntes_nuid"));
        meta.addProperty("WEVNSM", "1.0.0");
        meta.addProperty("WNMCID", CLIENT_ID);
        meta.addProperty("appver", APP_VERSION_FULL);
        meta.addProperty("channel", CHANNEL);
        meta.addProperty("deviceId", resolveDeviceId());
        meta.addProperty("mode", "MuoniumPlayer");
        meta.addProperty("os", "pc");
        meta.addProperty("osver", SYSTEM_VERSION);
        return meta.toString();
    }

    private static String buildRecords(List<LogRecord> records) {
        StringBuilder body = new StringBuilder();
        for (LogRecord record : records) {
            body.append(record.time).append('\u0001').append(record.action).append('\u0001')
                    .append(record.data.toString());
        }
        return body.toString();
    }

    private static byte[] encrypt(String metadata, String records) throws IOException {
        byte[] keyA = new byte[32];
        RANDOM.nextBytes(keyA);
        if ((keyA[0] & 0xFF) >= 0xA3) {
            keyA[0] = (byte) 0xA2;
        }
        byte[] keyB = rsaWrap(keyA);

        byte[] uuid = new byte[16];
        RANDOM.nextBytes(uuid);
        uuid[6] = (byte) ((uuid[6] & 0x0F) | 0x40);
        uuid[8] = (byte) ((uuid[8] & 0x3F) | 0x80);
        byte[] nonce = copyOfRange(uuid, 0, 12);
        int counter = readIntLE(uuid, 12) >>> 2;
        int baseSequence = RANDOM.nextInt(1 << 16);

        byte[] metaCipher = chacha20(keyB, counter, nonce, metadata.getBytes(StandardCharsets.UTF_8));
        if (metaCipher.length > 0xFFFF) {
            throw new IOException("NCBL metadata is too large");
        }
        ByteArrayOutputStream metaBlock = new ByteArrayOutputStream(metaCipher.length + 4);
        writeShortLE(metaBlock, META_BLOCK_TYPE);
        writeShortLE(metaBlock, metaCipher.length);
        metaBlock.write(metaCipher);

        byte[] compressed = compressZstd(records.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream frames = new ByteArrayOutputStream(compressed.length + 16);
        int sequence = baseSequence;
        for (int offset = 0; offset < compressed.length || offset == 0; offset += MAX_FRAME_LENGTH) {
            int length = Math.min(MAX_FRAME_LENGTH, Math.max(0, compressed.length - offset));
            byte[] frame = new byte[length];
            if (length > 0) {
                System.arraycopy(compressed, offset, frame, 0, length);
            }
            byte[] cipher = chacha20(keyA, counter, nonce, frame);
            writeShortLE(frames, cipher.length);
            writeIntLE(frames, sequence++);
            frames.write(cipher);
            if (compressed.length == 0) {
                break;
            }
        }

        byte[] metaBytes = metaBlock.toByteArray();
        byte[] trailing = frames.toByteArray();
        ByteBuffer header = ByteBuffer.allocate(HEADER_FIXED_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC);
        header.putInt(3);
        header.putShort((short) (HEADER_FIXED_LENGTH + metaBytes.length));
        header.put(uuid);
        header.put(keyB);
        header.putInt(baseSequence);
        header.putInt(sequence - 1);
        header.putInt(trailing.length);

        ByteArrayOutputStream output = new ByteArrayOutputStream(header.capacity() + metaBytes.length + trailing.length);
        output.write(header.array());
        output.write(metaBytes);
        output.write(trailing);
        return output.toByteArray();
    }

    private static UploadResult uploadPayload(String cookie, byte[] payload) throws IOException {
        String boundary = "----MuoniumPlayer" + UUID.randomUUID().toString().replace("-", "");
        String filename = "op_" + (10000 + RANDOM.nextInt(90000)) + "_0_"
                + Integer.toUnsignedString(RANDOM.nextInt());

        HttpsURLConnection connection = (HttpsURLConnection) new URL(UPLOAD_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Referer", "https://music.163.com/di");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 "
                + "NeteaseMusicDesktop/" + APP_VERSION);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "gzip,deflate");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.8");
        connection.setRequestProperty("Cookie", cookie);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = connection.getOutputStream()) {
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
            writeAscii(out, "Content-Type: multipart/form-data\r\n\r\n");
            out.write(payload);
            writeAscii(out, "\r\n--" + boundary + "--\r\n");
        }

        int httpStatus = connection.getResponseCode();
        String response = readResponse(connection, httpStatus);
        connection.disconnect();
        if (httpStatus < 200 || httpStatus >= 300) {
            String message = "HTTP " + httpStatus;
            System.err.println("[NCM] Client-log server returned " + message + ": " + response);
            return UploadResult.failure(0L, message);
        }

        try {
            JsonObject body = JsonUtils.parse(response, JsonObject.class);
            boolean accepted = body != null && body.has("code") && body.get("code").getAsInt() == 200
                    && body.has("data") && body.getAsJsonObject("data").has("successfiles")
                    && contains(body.getAsJsonObject("data").getAsJsonArray("successfiles"), filename);
            if (!accepted) {
                System.err.println("[NCM] Client-log server rejected listening file: " + response);
                return UploadResult.failure(0L, "服务端未确认 successfiles");
            }
            return UploadResult.success(0L);
        } catch (Throwable parseFailure) {
            System.err.println("[NCM] Invalid client-log response: " + response);
            return UploadResult.failure(0L, "服务端响应格式无效");
        }
    }

    private static boolean contains(JsonArray values, String expected) {
        if (values == null) return false;
        for (int i = 0; i < values.size(); i++) {
            if (expected.equals(values.get(i).getAsString())) return true;
        }
        return false;
    }

    private static String readResponse(HttpsURLConnection connection, int status) throws IOException {
        InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) return "";
        String encoding = safe(connection.getContentEncoding()).toLowerCase(Locale.ROOT);
        if (encoding.contains("gzip")) {
            input = new GZIPInputStream(input);
        } else if (encoding.contains("deflate")) {
            input = new InflaterInputStream(input);
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The provided API requires Zstandard, but this Java 8 / Forge 1.8.9 project
     * cannot use Node's built-in zlib implementation from the reference project.
     * We use the already bundled, relocated JNA runtime and package the 64-bit
     * zstd DLL with the mod instead of depending on an incompatible modern JVM API.
     */
    private static byte[] compressZstd(byte[] source) throws IOException {
        ZstdNative nativeLibrary = getZstd();
        long capacity = nativeLibrary.ZSTD_compressBound(source.length);
        if (capacity <= 0L || capacity > Integer.MAX_VALUE) {
            throw new IOException("Invalid Zstandard compression bound: " + capacity);
        }
        byte[] target = new byte[(int) capacity];
        long written = nativeLibrary.ZSTD_compress(target, target.length, source, source.length, 3);
        if (nativeLibrary.ZSTD_isError(written) != 0 || written < 0L || written > target.length) {
            Pointer error = nativeLibrary.ZSTD_getErrorName(written);
            throw new IOException("Zstandard compression failed: "
                    + (error == null ? String.valueOf(written) : error.getString(0)));
        }
        byte[] compressed = new byte[(int) written];
        System.arraycopy(target, 0, compressed, 0, compressed.length);
        return compressed;
    }

    private static ZstdNative getZstd() throws IOException {
        ZstdNative current = zstd;
        if (current != null) return current;
        synchronized (NeteaseClientLogUploader.class) {
            if (zstd != null) return zstd;
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    || (!architecture.contains("amd64") && !architecture.contains("x86_64"))) {
                throw new IOException("NCBL listening-history upload currently requires 64-bit Windows");
            }

            InputStream resource = NeteaseClientLogUploader.class.getResourceAsStream("/muonium/native/win64/zstd.dll");
            if (resource == null) {
                throw new IOException("Bundled zstd.dll is missing");
            }
            File nativeFile = Files.createTempFile("muonium-zstd-", ".dll").toFile();
            nativeFile.deleteOnExit();
            try (InputStream input = resource; OutputStream output = new FileOutputStream(nativeFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            }
            zstd = Native.load(nativeFile.getAbsolutePath(), ZstdNative.class);
            return zstd;
        }
    }
    private static byte[] rsaWrap(byte[] keyA) {
        BigInteger value = new BigInteger(1, keyA).modPow(RSA_E, RSA_N);
        byte[] source = value.toByteArray();
        byte[] output = new byte[32];
        int sourceOffset = Math.max(0, source.length - output.length);
        int copyLength = Math.min(source.length, output.length);
        System.arraycopy(source, sourceOffset, output, output.length - copyLength, copyLength);
        return output;
    }

    private static byte[] chacha20(byte[] key, int counter, byte[] nonce, byte[] data) {
        byte[] output = new byte[data.length];
        for (int offset = 0; offset < data.length; offset += 64) {
            byte[] keyStream = chachaBlock(key, counter + (offset >>> 6), nonce);
            int end = Math.min(offset + 64, data.length);
            for (int i = offset; i < end; i++) {
                output[i] = (byte) (data[i] ^ keyStream[i - offset]);
            }
        }
        return output;
    }

    private static byte[] chachaBlock(byte[] key, int counter, byte[] nonce) {
        int[] state = new int[16];
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) state[4 + i] = readIntLE(key, i * 4);
        state[12] = counter;
        state[13] = readIntLE(nonce, 0);
        state[14] = readIntLE(nonce, 4);
        state[15] = readIntLE(nonce, 8);

        int[] work = state.clone();
        for (int i = 0; i < 10; i++) {
            quarterRound(work, 0, 4, 8, 12);
            quarterRound(work, 1, 5, 9, 13);
            quarterRound(work, 2, 6, 10, 14);
            quarterRound(work, 3, 7, 11, 15);
            quarterRound(work, 0, 5, 10, 15);
            quarterRound(work, 1, 6, 11, 12);
            quarterRound(work, 2, 7, 8, 13);
            quarterRound(work, 3, 4, 9, 14);
        }

        ByteBuffer output = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) output.putInt(work[i] + state[i]);
        return output.array();
    }

    private static void quarterRound(int[] state, int a, int b, int c, int d) {
        state[a] += state[b];
        state[d] ^= state[a];
        state[d] = Integer.rotateLeft(state[d], 16);
        state[c] += state[d];
        state[b] ^= state[c];
        state[b] = Integer.rotateLeft(state[b], 12);
        state[a] += state[b];
        state[d] ^= state[a];
        state[d] = Integer.rotateLeft(state[d], 8);
        state[c] += state[d];
        state[b] ^= state[c];
        state[b] = Integer.rotateLeft(state[b], 7);
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeShortLE(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static void writeIntLE(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] copyOfRange(byte[] source, int from, int to) {
        byte[] output = new byte[to - from];
        System.arraycopy(source, from, output, 0, output.length);
        return output;
    }

    private static String resolveSourceId(PlayList source) {
        return source != null && source.getPlatform() == MusicPlatform.NETEASE && source.getId() > 0L
                ? String.valueOf(source.getId()) : "";
    }

    private static int toResourceSeconds(Music song) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, song.getDuration() / 1000L));
    }

    private static int resolveVipType() {
        User profile = CloudMusic.profile;
        return profile == null ? 0 : profile.getVip();
    }

    private static String resolveDeviceId() {
        String current = deviceId;
        if (current != null && !current.isEmpty()) return current;
        synchronized (NeteaseClientLogUploader.class) {
            if (deviceId == null || deviceId.isEmpty()) {
                try {
                    deviceId = DeviceIdGenerator.generate();
                } catch (Throwable ignored) {
                    deviceId = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
                }
            }
            return deviceId;
        }
    }

    private static Map<String, String> parseCookies(String cookie) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<String, String>();
        for (String part : cookie.split(";")) {
            int separator = part.indexOf('=');
            if (separator <= 0) continue;
            values.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
        }
        return values;
    }

    private static void putIfPresent(JsonObject target, String name, String value) {
        if (value != null && !value.trim().isEmpty()) target.addProperty(name, value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    /** Native zstd C API; Java long is used for the size_t arguments on Win64. */
    private interface ZstdNative extends Library {
        long ZSTD_compressBound(long sourceSize);

        long ZSTD_compress(byte[] destination, long destinationCapacity,
                           byte[] source, long sourceSize, int compressionLevel);

        int ZSTD_isError(long code);

        Pointer ZSTD_getErrorName(long code);
    }
    static final class UploadResult {
        private final boolean success;
        private final long elapsedMillis;
        private final String message;

        private UploadResult(boolean success, long elapsedMillis, String message) {
            this.success = success;
            this.elapsedMillis = Math.max(0L, elapsedMillis);
            this.message = message == null || message.trim().isEmpty() ? "未知错误" : message;
        }

        static UploadResult success(long elapsedMillis) {
            return new UploadResult(true, elapsedMillis, "");
        }

        static UploadResult failure(long elapsedMillis, String message) {
            return new UploadResult(false, elapsedMillis, message);
        }

        UploadResult withElapsed(long elapsedMillis) {
            return new UploadResult(success, elapsedMillis, message);
        }

        boolean isSuccess() {
            return success;
        }

        long getElapsedMillis() {
            return elapsedMillis;
        }

        String getMessage() {
            return message;
        }
    }

    private static final class LogRecord {
        private final long time;
        private final String action;
        private final JsonObject data;

        private LogRecord(long time, String action, JsonObject data) {
            this.time = time;
            this.action = action;
            this.data = data;
        }
    }
}

