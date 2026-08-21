package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.utils.Tuple;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in client for the GD Studio aggregated music API (https://music-api.gdstudio.xyz).
 *
 * <p>Each request uses the platform and identifiers returned by the preceding API search.
 * A local rolling-window limiter caps outbound traffic at the documented fifty requests per five minutes.
 * The service calls only the documented search, URL, artwork and lyric endpoints.</p>
 */
public final class GdStudioMusicService {

    public static final String API_ENDPOINT = "https://music-api.gdstudio.xyz/api.php";

    private static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static final int TIMEOUT_MILLIS = 8000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEARCH_COUNT = 20;
    private static final long REQUEST_WINDOW_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_REQUESTS_PER_WINDOW = 50;
    private static final long SEARCH_CACHE_MILLIS = 15_000L;
    private static final long COVER_CACHE_MILLIS = 6L * 60L * 60L * 1000L;
    private static final long LYRIC_CACHE_MILLIS = 6L * 60L * 60L * 1000L;
    private static final Object REQUEST_LOCK = new Object();
    private static final Deque<Long> REQUEST_TIMESTAMPS = new ArrayDeque<>();
    private static final Object CACHE_LOCK = new Object();
    private static final Map<String, CacheEntry<List<GdTrack>>> SEARCH_CACHE = new LinkedHashMap<>();
    private static final Map<String, CacheEntry<String>> COVER_CACHE = new LinkedHashMap<>();
    private static final Map<String, CacheEntry<Tuple<String, String>>> LYRIC_CACHE = new LinkedHashMap<>();
    private static final List<Platform> PLATFORMS = buildPlatforms();
    private static final Map<String, Platform> PLATFORM_BY_KEY = indexPlatforms();

    private GdStudioMusicService() {
    }

    /** Runtime guidance shown in the source menu; entries remain selectable if upstream recovers. */
    public enum PlatformStatus {
        RECOMMENDED("稳定 · 搜索、封面、播放、歌词", 0x75D8A0),
        PLAYABLE("可播放 · 歌词可能为空", 0x82C7FF),
        EXPERIMENTAL("实验性 · 播放链接可能为空", 0xE7C26B),
        UNAVAILABLE("暂未开放 · 接口恢复后可直接重试", 0xAEB5C4);

        public final String description;
        public final int color;

        PlatformStatus(String description, int color) {
            this.description = description;
            this.color = color;
        }
    }

    /** One selectable platform exposed by the GD Studio API. */
    public static final class Platform {
        public final String key;
        public final String displayName;
        public final PlatformStatus status;

        private Platform(String key, String displayName, PlatformStatus status) {
            this.key = key;
            this.displayName = displayName;
            this.status = status == null ? PlatformStatus.UNAVAILABLE : status;
        }
    }

    /** Search result adapted from the GD Studio search response. */
    public static final class GdTrack {
        public final String id;
        public final String name;
        public final String artist;
        public final String album;
        public final String picId;
        public final String lyricId;
        public final String source;

        private GdTrack(String id, String name, String artist, String album, String picId, String lyricId, String source) {
            this.id = id;
            this.name = name;
            this.artist = artist;
            this.album = album;
            this.picId = picId;
            this.lyricId = lyricId;
            this.source = source;
        }
    }

    public static List<Platform> getPlatforms() {
        return PLATFORMS;
    }

    public static String displayName(String key) {
        Platform platform = PLATFORM_BY_KEY.get(safe(key).toLowerCase(Locale.ROOT));
        return platform == null ? safe(key).toUpperCase(Locale.ROOT) : platform.displayName;
    }

    public static boolean isKnownPlatform(String key) {
        return PLATFORM_BY_KEY.containsKey(safe(key).toLowerCase(Locale.ROOT));
    }

    /** GET types=search. The documented default page size is 20; do not request more than that. */
    public static List<GdTrack> search(String source, String keyword, int count, int page) throws Exception {
        String platform = requirePlatform(source);
        String name = safe(keyword);
        if (name.isEmpty()) return Collections.emptyList();

        int pageSize = Math.max(1, Math.min(MAX_SEARCH_COUNT, count <= 0 ? MAX_SEARCH_COUNT : count));
        int pageNumber = Math.max(1, page);
        String cacheKey = platform + '\n' + name.toLowerCase(Locale.ROOT) + '\n' + pageSize + '\n' + pageNumber;
        List<GdTrack> cached = cachedSearch(cacheKey);
        if (cached != null) return cached;

        StringBuilder query = new StringBuilder("types=search");
        append(query, "source", platform);
        append(query, "name", name);
        append(query, "count", String.valueOf(pageSize));
        append(query, "pages", String.valueOf(pageNumber));
        JsonElement root = json(query.toString());
        if (root == null) return Collections.emptyList();
        if (root.isJsonObject()) throw new IllegalStateException(firstError(root.getAsJsonObject()));

        List<GdTrack> result = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String id = text(item, "id");
                if (id.isEmpty()) continue;
                String resultSource = normalizePlatform(text(item, "source"));
                result.add(new GdTrack(id, text(item, "name"), artists(item.get("artist")),
                        text(item, "album"), text(item, "pic_id"), text(item, "lyric_id"),
                        resultSource.isEmpty() ? platform : resultSource));
            }
        }
        List<GdTrack> immutable = Collections.unmodifiableList(result);
        cacheSearch(cacheKey, immutable);
        return new ArrayList<>(immutable);
    }

    /** GET types=url. The requested value is one of the documented bitrates only. */
    public static JsonObject requestUrl(String source, String trackId, int br) throws Exception {
        String platform = requirePlatform(source);
        String id = safe(trackId);
        if (id.isEmpty()) return null;
        StringBuilder query = new StringBuilder("types=url");
        append(query, "source", platform);
        append(query, "id", id);
        append(query, "br", String.valueOf(documentedBitrate(br)));
        JsonElement root = json(query.toString());
        return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    /** GET types=pic with only the documented 300/500 size values. */
    public static String getPicUrl(String source, String picId, int size) throws Exception {
        String platform = requirePlatform(source);
        String id = safe(picId);
        if (id.isEmpty()) return "";
        int imageSize = size == 500 ? 500 : 300;
        String cacheKey = platform + ':' + id + ':' + imageSize;
        String cached = cached(COVER_CACHE, cacheKey);
        if (cached != null) return cached;

        StringBuilder query = new StringBuilder("types=pic");
        append(query, "source", platform);
        append(query, "id", id);
        append(query, "size", String.valueOf(imageSize));
        JsonElement root = json(query.toString());
        String url = root != null && root.isJsonObject() ? text(root.getAsJsonObject(), "url") : "";
        if (!url.isEmpty()) cache(COVER_CACHE, cacheKey, url, COVER_CACHE_MILLIS);
        return url;
    }

    /** GET types=lyric: original LRC plus optional translated LRC. */
    public static Tuple<String, String> getLyric(String source, String lyricId) throws Exception {
        String platform = requirePlatform(source);
        String id = safe(lyricId);
        if (id.isEmpty()) return null;
        String cacheKey = platform + ':' + id;
        Tuple<String, String> cached = cached(LYRIC_CACHE, cacheKey);
        if (cached != null) return cached;

        StringBuilder query = new StringBuilder("types=lyric");
        append(query, "source", platform);
        append(query, "id", id);
        JsonElement root = json(query.toString());
        if (root == null || !root.isJsonObject()) return null;
        JsonObject object = root.getAsJsonObject();
        Tuple<String, String> lyric = new Tuple<>(text(object, "lyric"), text(object, "tlyric"));
        cache(LYRIC_CACHE, cacheKey, lyric, LYRIC_CACHE_MILLIS);
        return lyric;
    }

    /**
     * Resolves exactly the selected source/id pair from the search response. The API returns the
     * actual bitrate, so a single documented request is sufficient and avoids fallback bursts.
     */
    public static ResolveResult resolveTrack(String source, String trackId, Quality quality) throws Exception {
        String platform = requirePlatform(source);
        JsonObject object = requestUrl(platform, trackId, bitrateFor(quality));
        String url = object == null ? "" : text(object, "url");
        return url.isEmpty() ? null : new ResolveResult(url, inferFormat(url), platform);
    }

    /** Result of a GD Studio playback resolution for the selected source. */
    public static final class ResolveResult {
        public final String url;
        public final String format;
        public final String platform;

        private ResolveResult(String url, String format, String platform) {
            this.url = url;
            this.format = format;
            this.platform = platform;
        }
    }

    private static int bitrateFor(Quality quality) {
        Quality requested = quality == null ? Quality.LOSSLESS : quality;
        switch (requested) {
            case STANDARD:
                return 128;
            case HIGHER:
                return 192;
            case EXHIGH:
                return 320;
            case LOSSLESS:
                return 740;
            default:
                return 999;
        }
    }

    private static int documentedBitrate(int bitrate) {
        switch (bitrate) {
            case 128:
            case 192:
            case 320:
            case 740:
            case 999:
                return bitrate;
            default:
                return 999;
        }
    }

    private static String inferFormat(String url) {
        String value = safe(url).toLowerCase(Locale.ROOT);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) return value.substring(dot + 1);
        return "mp3";
    }

    private static String requirePlatform(String value) {
        String platform = normalizePlatform(value);
        if (platform.isEmpty()) throw new IllegalArgumentException("未选择有效的 GD音乐台 平台");
        return platform;
    }

    private static String normalizePlatform(String value) {
        String platform = safe(value).toLowerCase(Locale.ROOT);
        return isKnownPlatform(platform) ? platform : "";
    }

    private static List<GdTrack> cachedSearch(String key) {
        List<GdTrack> result = cached(SEARCH_CACHE, key);
        return result == null ? null : new ArrayList<>(result);
    }

    private static void cacheSearch(String key, List<GdTrack> value) {
        cache(SEARCH_CACHE, key, value, SEARCH_CACHE_MILLIS);
    }

    private static <T> T cached(Map<String, CacheEntry<T>> cache, String key) {
        synchronized (CACHE_LOCK) {
            CacheEntry<T> entry = cache.get(key);
            if (entry == null) return null;
            if (entry.expiresAt <= System.currentTimeMillis()) {
                cache.remove(key);
                return null;
            }
            return entry.value;
        }
    }

    private static <T> void cache(Map<String, CacheEntry<T>> cache, String key, T value, long lifetime) {
        if (value == null) return;
        synchronized (CACHE_LOCK) {
            if (cache.size() >= 128) cache.clear();
            cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + lifetime));
        }
    }

    /** Enforces GD Studio's documented rolling request budget before opening a connection. */
    private static void acquireRequestSlot() {
        synchronized (REQUEST_LOCK) {
            long now = System.currentTimeMillis();
            long cutoff = now - REQUEST_WINDOW_MILLIS;
            while (!REQUEST_TIMESTAMPS.isEmpty() && REQUEST_TIMESTAMPS.peekFirst() <= cutoff) {
                REQUEST_TIMESTAMPS.removeFirst();
            }
            if (REQUEST_TIMESTAMPS.size() >= MAX_REQUESTS_PER_WINDOW) {
                long retryAfter = Math.max(1L, REQUEST_TIMESTAMPS.peekFirst() + REQUEST_WINDOW_MILLIS - now);
                throw new IllegalStateException("GD音乐台请求已达 5 分钟 50 次上限，请在 "
                        + ((retryAfter + 999L) / 1000L) + " 秒后重试");
            }
            REQUEST_TIMESTAMPS.addLast(now);
        }
    }

    private static final class CacheEntry<T> {
        private final T value;
        private final long expiresAt;

        private CacheEntry(T value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private static JsonElement json(String query) throws Exception {
        acquireRequestSlot();
        HttpURLConnection connection = (HttpURLConnection) new URL(API_ENDPOINT + "?" + query).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("User-Agent", AGENT);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("GD音乐台返回 HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return new JsonParser().parse(new String(readLimited(input), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > MAX_BYTES) throw new IllegalStateException("GD音乐台响应过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void append(StringBuilder builder, String key, String value) {
        if (safe(value).isEmpty()) return;
        if (builder.length() > 0) builder.append('&');
        try {
            builder.append(key).append('=').append(URLEncoder.encode(safe(value), "UTF-8"));
        } catch (Exception ignored) {
            builder.append(key).append('=');
        }
    }

    private static String artists(JsonElement element) {
        if (element == null || !element.isJsonArray()) return "";
        StringBuilder result = new StringBuilder();
        for (JsonElement value : element.getAsJsonArray()) {
            String name = value.isJsonPrimitive() ? value.getAsString() : "";
            if (name.trim().isEmpty()) continue;
            if (result.length() > 0) result.append('、');
            result.append(name.trim());
        }
        return result.toString();
    }

    private static String firstError(JsonObject object) {
        String message = text(object, "msg");
        if (message.isEmpty()) message = text(object, "message");
        if (message.isEmpty()) message = text(object, "error");
        return message.isEmpty() ? "平台暂不可用" : message;
    }

    private static String text(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<Platform> buildPlatforms() {
        List<Platform> platforms = new ArrayList<>();
        // Ordered by current end-to-end verification. All documented parameters are kept so a
        // source that the upstream service restores becomes selectable without a client update.
        platforms.add(new Platform("netease", "网易云音乐", PlatformStatus.RECOMMENDED));
        platforms.add(new Platform("joox", "JOOX", PlatformStatus.RECOMMENDED));
        platforms.add(new Platform("bilibili", "哔哩哔哩", PlatformStatus.PLAYABLE));
        platforms.add(new Platform("kuwo", "酷我音乐", PlatformStatus.EXPERIMENTAL));
        platforms.add(new Platform("tencent", "QQ音乐", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("tidal", "Tidal", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("qobuz", "Qobuz", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("apple", "Apple Music", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("ytmusic", "YouTube Music", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("spotify", "Spotify", PlatformStatus.UNAVAILABLE));
        return platforms;
    }

    private static Map<String, Platform> indexPlatforms() {
        Map<String, Platform> result = new LinkedHashMap<>();
        for (Platform platform : PLATFORMS) result.put(platform.key, platform);
        return result;
    }
}
