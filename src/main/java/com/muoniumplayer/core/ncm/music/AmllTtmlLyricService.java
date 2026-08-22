package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AMLL TTML 词库(api.amll.dev)逐字歌词解析。
 *
 * <p>这个词库里的歌词是人工校对的 Apple Music 风味 TTML,音节级时间轴 + 翻译 + 罗马音,质量高于任何
 * 平台接口返回的逐字歌词,因此在歌词解析链里排在最前面。普通 LRC 只是兜底:命中这里之后调用方不会再去
 * 请求任何平台歌词。</p>
 *
 * <p>匹配顺序严格按接口文档:先用平台 ID 精确取(netease/QQ/Apple/Spotify 各自的 ID 参数,交集匹配,
 * 只返回最新一条),取不到再按曲名搜索并在本地打分。<b>宁可没有歌词,也不给错歌的歌词</b>——搜索结果
 * 必须曲名归一化后相等或互相包含,且歌手能对上,才会被采用。</p>
 *
 * <p>兜底保护:</p>
 * <ul>
 *   <li>请求间隔不低于 25 毫秒,远低于文档标注的单 IP 50 次/秒上限。</li>
 *   <li>404(词库没有这首歌)按"未命中"处理并短期负缓存,不计入故障;连接失败、读取超时、429 等
 *       连续 3 次会打开熔断,5 分钟内不再请求,歌词加载因此永远不会拖慢播放。</li>
 *   <li>命中结果缓存的是原始 TTML 文本而不是解析后的 LyricLine:渲染对象带有滚动/透明度状态,
 *       跨次播放复用同一批对象会把上一次的动画状态带进新的一次播放。</li>
 *   <li>线程被中断(调用方已放弃)时立即返回,不再消耗请求。</li>
 * </ul>
 */
final class AmllTtmlLyricService {

    private static final String GET_ENDPOINT = "https://api.amll.dev/v1/lyrics/get";
    private static final String SEARCH_ENDPOINT = "https://api.amll.dev/v1/lyrics/search";

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 8_000;
    /** 文档:单 IP 平均 50 次/秒。25 毫秒的最小间隔留出一半余量。 */
    private static final long MIN_REQUEST_INTERVAL_MILLIS = 25L;
    /** 命中缓存时长。歌词正文对固定 id 永不变化,但曲目到 id 的映射会随词库更新。 */
    private static final long HIT_CACHE_MILLIS = 6L * 60L * 60L * 1_000L;
    private static final long MISS_CACHE_MILLIS = 10L * 60L * 1_000L;
    private static final int CACHE_CAPACITY = 96;
    private static final int BREAKER_FAILURE_THRESHOLD = 3;
    private static final long BREAKER_COOLDOWN_MILLIS = 5L * 60L * 1_000L;
    private static final int SEARCH_PAGE_SIZE = 10;

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<String, CacheEntry>();
    private static final Object REQUEST_LOCK = new Object();
    private static final AtomicInteger CONSECUTIVE_FAILURES = new AtomicInteger();

    private static long nextRequestAtMillis;
    private static volatile long breakerOpenUntilMillis;

    private AmllTtmlLyricService() {
    }

    /**
     * 解析这首歌的逐字歌词。返回空列表表示"词库里没有 / 不可用",调用方应当继续走普通歌词。
     * 任何异常都被吞掉,歌词永远不该让播放失败。
     */
    static List<LyricLine> resolve(Music music) {
        if (music == null || !isEnabled()) return Collections.emptyList();
        if (Thread.currentThread().isInterrupted()) return Collections.emptyList();

        final String cacheKey = music.getStableKey();
        long now = System.currentTimeMillis();

        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && now < cached.expiresAtMillis) {
            return parse(cached.ttml, cacheKey);
        }
        if (now < breakerOpenUntilMillis) return Collections.emptyList();

        String ttml = null;
        try {
            ttml = fetchByPlatformId(music);
            if (ttml == null) ttml = fetchBySearch(music);
        } catch (Throwable failure) {
            System.err.println("[AMLL] Lyric lookup failed for " + cacheKey + ": " + failure.getMessage());
        }

        store(cacheKey, ttml);
        return parse(ttml, cacheKey);
    }

    private static List<LyricLine> parse(String ttml, String cacheKey) {
        if (ttml == null || ttml.trim().isEmpty()) return Collections.emptyList();
        List<LyricLine> parsed = new ArrayList<LyricLine>();
        try {
            LyricParser.parseTtml(ttml, parsed);
        } catch (Throwable failure) {
            System.err.println("[AMLL] TTML parse failed for " + cacheKey + ": " + failure.getMessage());
            return Collections.emptyList();
        }
        if (!LyricParser.hasWordByWordTiming(parsed)) {
            // 行级 TTML 不比平台歌词更好,交回给普通歌词链路,免得白白丢掉翻译。
            return Collections.emptyList();
        }
        return parsed;
    }

    private static boolean isEnabled() {
        try {
            return MuoniumPlayerExtension.getInstance().musicLyrics.preferAmllLyrics.getValue();
        } catch (Throwable ignored) {
            return true;   // 设置尚未初始化时按默认开启处理
        }
    }

    // ── 平台 ID 精确匹配 ────────────────────────────────────────────────────

    private static String fetchByPlatformId(Music music) {
        for (String[] parameter : platformIdParameters(music)) {
            if (Thread.currentThread().isInterrupted()) return null;
            Map<String, String> params = new LinkedHashMap<String, String>();
            params.put(parameter[0], parameter[1]);
            JsonObject data = request(GET_ENDPOINT, params);
            String ttml = lyricsOf(data);
            if (ttml != null) {
                System.out.println("[AMLL] " + music.getName() + " matched by " + parameter[0] + "="
                        + parameter[1] + " (" + text(data, "filename") + ")");
                return ttml;
            }
        }
        return null;
    }

    /** 本项目能提供的平台 ID,按可信度排序。未知平台(kuwo/tidal/joox/bilibili...)交给曲名搜索。 */
    private static List<String[]> platformIdParameters(Music music) {
        List<String[]> parameters = new ArrayList<String[]>();
        String sourceId = safe(music.getSourceId());
        if (music.isNetease()) {
            addParameter(parameters, "ncmMusicId", String.valueOf(music.getId()));
            addParameter(parameters, "ncmMusicId", sourceId);
        } else if (music.isQQ()) {
            addParameter(parameters, "qqMusicId", safe(music.getSourceMid()));
            addParameter(parameters, "qqMusicId", sourceId);
        } else if (music.isGd()) {
            String platform = safe(music.getGdPlatform()).toLowerCase(Locale.ROOT);
            if (platform.equals("netease")) addParameter(parameters, "ncmMusicId", sourceId);
            else if (platform.equals("tencent")) addParameter(parameters, "qqMusicId", sourceId);
            else if (platform.equals("apple")) addParameter(parameters, "appleMusicId", sourceId);
            else if (platform.equals("spotify")) addParameter(parameters, "spotifyId", sourceId);
        }
        return parameters;
    }

    private static void addParameter(List<String[]> parameters, String name, String value) {
        String id = safe(value).trim();
        if (id.isEmpty() || id.equals("0")) return;
        for (String[] existing : parameters) {
            if (existing[0].equals(name) && existing[1].equals(id)) return;
        }
        parameters.add(new String[]{name, id});
    }

    // ── 曲名搜索兜底 ────────────────────────────────────────────────────────

    private static String fetchBySearch(Music music) {
        String title = safe(music.getName()).trim();
        if (title.isEmpty()) return null;
        String artist = firstArtist(music);

        JsonArray items = search(title);
        String stripped = stripBracketSuffix(title);
        if ((items == null || items.size() == 0) && !stripped.equals(title)) {
            items = search(stripped);
        }
        if (items == null || items.size() == 0) return null;

        JsonObject best = null;
        int bestScore = 0;
        for (int i = 0; i < items.size(); i++) {
            JsonElement element = items.get(i);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            int score = score(item, title, artist);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        if (best == null) return null;

        String id = text(best, "id");
        if (id.isEmpty()) return null;
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("id", id);
        JsonObject data = request(GET_ENDPOINT, params);
        String ttml = lyricsOf(data);
        if (ttml != null) {
            System.out.println("[AMLL] " + music.getName() + " matched by search score " + bestScore
                    + " (" + text(data, "filename") + ")");
        }
        return ttml;
    }

    private static JsonArray search(String title) {
        if (Thread.currentThread().isInterrupted()) return null;
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("musicName", title);
        params.put("pageSize", String.valueOf(SEARCH_PAGE_SIZE));
        JsonObject data = request(SEARCH_ENDPOINT, params);
        if (data == null || !data.has("items") || !data.get("items").isJsonArray()) return null;
        return data.getAsJsonArray("items");
    }

    /**
     * 候选打分。3 = 曲名完全一致且歌手对得上,2 = 曲名完全一致且本地没有歌手信息,
     * 1 = 曲名互相包含且歌手对得上。0 表示拒绝——错歌的逐字歌词比没有歌词糟糕得多。
     */
    private static int score(JsonObject item, String title, String artist) {
        String normalizedTitle = normalize(title);
        if (normalizedTitle.isEmpty()) return 0;

        boolean exactTitle = false;
        boolean looseTitle = false;
        for (String candidate : strings(item, "musicNames")) {
            String normalized = normalize(candidate);
            if (normalized.isEmpty()) continue;
            if (normalized.equals(normalizedTitle)) exactTitle = true;
            else if (normalized.contains(normalizedTitle) || normalizedTitle.contains(normalized)) looseTitle = true;
        }
        if (!exactTitle && !looseTitle) return 0;

        String normalizedArtist = normalize(artist);
        if (normalizedArtist.isEmpty()) return exactTitle ? 2 : 0;

        for (String candidate : strings(item, "artistNames")) {
            String normalized = normalize(candidate);
            if (normalized.isEmpty()) continue;
            if (normalized.equals(normalizedArtist)
                    || normalized.contains(normalizedArtist) || normalizedArtist.contains(normalized)) {
                return exactTitle ? 3 : 1;
            }
        }
        return 0;
    }

    private static String firstArtist(Music music) {
        try {
            String artists = safe(music.getArtistsName());
            // Music 在没有歌手元数据时返回占位的 "Unknown",拿它去比对会把正确的候选全部否掉。
            if (artists.isEmpty() || artists.equalsIgnoreCase("Unknown")) return "";
            String[] parts = artists.split("\\s*[/、,&]\\s*");
            for (String part : parts) {
                if (part != null && !part.trim().isEmpty()) return part.trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String stripBracketSuffix(String title) {
        String stripped = title.replaceAll("[(\\uff08\\[【].*?[)\\uff09\\]】]", " ").trim();
        return stripped.isEmpty() ? title : stripped;
    }

    /** 归一化:忽略大小写、空白与标点,这些在不同平台的曲名里几乎总是不一致。 */
    private static String normalize(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder();
        String lower = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char character = lower.charAt(i);
            if (Character.isLetterOrDigit(character)) builder.append(character);
        }
        return builder.toString();
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    private static JsonObject request(String endpoint, Map<String, String> params) {
        if (Thread.currentThread().isInterrupted()) return null;
        InputStream stream = null;
        try {
            throttle();
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Accept", "application/json");
            headers.put("User-Agent", "MuoniumPlayer/1.1 (Minecraft mod; +https://amll.dev)");
            stream = HttpUtils.get(endpoint, params, headers, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
            if (stream == null) {
                noteFailure("empty response");
                return null;
            }
            String body = HttpUtils.readString(stream);
            noteSuccess();
            JsonObject root = JsonUtils.toJsonObject(body);
            if (root == null || !root.has("data") || !root.get("data").isJsonObject()) return null;
            return root.getAsJsonObject("data");
        } catch (Throwable failure) {
            int status = statusCodeOf(failure);
            if (status == 404) return null;   // 词库里没有这首歌,不是故障
            noteFailure(failure.getMessage());
            return null;
        } finally {
            closeQuietly(stream);
        }
    }

    /** HttpUtils 对 >= 300 的响应直接抛 RuntimeException,状态码只能从消息里取回。 */
    private static int statusCodeOf(Throwable failure) {
        String message = failure == null || failure.getMessage() == null ? "" : failure.getMessage();
        int marker = message.lastIndexOf("Response code is ");
        if (marker < 0) return -1;
        try {
            return Integer.parseInt(message.substring(marker + "Response code is ".length()).trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void throttle() {
        synchronized (REQUEST_LOCK) {
            long now = System.currentTimeMillis();
            if (now < nextRequestAtMillis) {
                try {
                    Thread.sleep(nextRequestAtMillis - now);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            nextRequestAtMillis = System.currentTimeMillis() + MIN_REQUEST_INTERVAL_MILLIS;
        }
    }

    private static void noteSuccess() {
        CONSECUTIVE_FAILURES.set(0);
    }

    private static void noteFailure(String reason) {
        if (CONSECUTIVE_FAILURES.incrementAndGet() >= BREAKER_FAILURE_THRESHOLD) {
            CONSECUTIVE_FAILURES.set(0);
            breakerOpenUntilMillis = System.currentTimeMillis() + BREAKER_COOLDOWN_MILLIS;
            System.err.println("[AMLL] Lyric API unreachable (" + reason + "), pausing lookups for "
                    + (BREAKER_COOLDOWN_MILLIS / 60_000L) + " minutes");
        }
    }

    // ── 缓存 ───────────────────────────────────────────────────────────────

    private static void store(String cacheKey, String ttml) {
        long expiry = System.currentTimeMillis() + (ttml == null ? MISS_CACHE_MILLIS : HIT_CACHE_MILLIS);
        if (CACHE.size() >= CACHE_CAPACITY) evictOldest();
        CACHE.put(cacheKey, new CacheEntry(ttml, expiry));
    }

    private static void evictOldest() {
        String oldestKey = null;
        long oldestExpiry = Long.MAX_VALUE;
        for (Map.Entry<String, CacheEntry> entry : CACHE.entrySet()) {
            if (entry.getValue().expiresAtMillis < oldestExpiry) {
                oldestExpiry = entry.getValue().expiresAtMillis;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) CACHE.remove(oldestKey);
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    private static String lyricsOf(JsonObject data) {
        String lyrics = text(data, "lyrics");
        return lyrics.isEmpty() ? null : lyrics;
    }

    private static String text(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
            return object.get(property).getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<String> strings(JsonObject object, String property) {
        List<String> values = new ArrayList<String>();
        try {
            if (object == null || !object.has(property) || !object.get(property).isJsonArray()) return values;
            JsonArray array = object.getAsJsonArray(property);
            for (int i = 0; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (element != null && !element.isJsonNull()) values.add(element.getAsString());
            }
        } catch (Throwable ignored) {
        }
        return values;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    private static final class CacheEntry {
        /** null 表示已知未命中。 */
        private final String ttml;
        private final long expiresAtMillis;

        private CacheEntry(String ttml, long expiresAtMillis) {
            this.ttml = ttml;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
