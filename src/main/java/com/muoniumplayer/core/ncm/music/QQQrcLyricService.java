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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ 音乐 QRC 逐字歌词。歌词链路里排在 AMLL TTML 词库之后、所有普通歌词之前。
 *
 * <p>为什么需要它:AMLL 词库是人工投稿的,覆盖率有限;而 QQ 音乐自己的 QRC 是音节级时间轴,覆盖面
 * 比任何开放词库都大。所以顺序是 <b>AMLL TTML(逐字) → QQ QRC(逐字) → 普通歌词(兜底)</b>,
 * 逐字歌词永远优先,只有两条逐字链路都拿不到东西时才会退回行级 LRC。</p>
 *
 * <p>流程:</p>
 * <ol>
 *   <li>能直接拿到 QQ 数字 songid 的曲目(QQ 源、GD 的 tencent 平台)直接用它请求;</li>
 *   <li>否则用 {@code search_for_qq_cp} 搜索并在本地打分,<b>宁可没有歌词,也不给错歌的歌词</b>;</li>
 *   <li>{@code music.musichallSong.PlayLyricInfo} 取回 lyric/trans/roma 三段密文,
 *       交给 {@link QrcCipher} 解密 + inflate;</li>
 *   <li>解出的 XML 里 {@code LyricContent} 属性才是 QRC 正文,解析后<b>必须真的是逐字</b>
 *       ({@link LyricParser#hasWordByWordTiming})才会被采用——行级结果不如把翻译完整的平台歌词留给兜底链路。</li>
 * </ol>
 *
 * <p>兜底保护与 {@link AmllTtmlLyricService} 一致:最小请求间隔、连续失败熔断、命中/未命中缓存
 * (缓存原始文本而不是 LyricLine,渲染对象带动画状态不能跨播放复用)、线程中断即返回、
 * 任何异常都只是"没有逐字歌词",绝不影响播放。</p>
 */
final class QQQrcLyricService {

    private static final String SEARCH_ENDPOINT = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp";
    private static final String LYRIC_ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String SEARCH_REFERER = "http://m.y.qq.com";
    private static final String LYRIC_REFERER = "https://y.qq.com/portal/player.html";
    private static final String MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 8_000;
    /** QQ 没有公开限流说明,这里按"每秒最多 5 次"自我约束。 */
    private static final long MIN_REQUEST_INTERVAL_MILLIS = 200L;
    private static final long HIT_CACHE_MILLIS = 6L * 60L * 60L * 1_000L;
    private static final long MISS_CACHE_MILLIS = 30L * 60L * 1_000L;
    private static final int CACHE_CAPACITY = 64;
    private static final int BREAKER_FAILURE_THRESHOLD = 3;
    private static final long BREAKER_COOLDOWN_MILLIS = 5L * 60L * 1_000L;
    private static final int SEARCH_PAGE_SIZE = 10;
    private static final String LYRIC_CONTENT_MARKER = "LyricContent=\"";

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<String, CacheEntry>();
    private static final Object REQUEST_LOCK = new Object();
    private static final AtomicInteger CONSECUTIVE_FAILURES = new AtomicInteger();
    /**
     * 本次解析途中是否出现过接口层面的失败。歌词解析是异步的(automix 预备还会并发解析下一首),
     * 所以这个标记必须是线程私有的。
     */
    private static final ThreadLocal<Boolean> TRANSIENT_FAILURE = new ThreadLocal<Boolean>();

    private static long nextRequestAtMillis;
    private static volatile long breakerOpenUntilMillis;

    private QQQrcLyricService() {
    }

    /**
     * 解析这首歌的 QRC 逐字歌词。返回空列表表示"没有 / 不是逐字 / 接口不可用",调用方继续走普通歌词。
     */
    static List<LyricLine> resolve(Music music) {
        if (music == null || !isEnabled()) return Collections.emptyList();
        if (Thread.currentThread().isInterrupted()) return Collections.emptyList();

        final String cacheKey = music.getStableKey();
        long now = System.currentTimeMillis();

        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && now < cached.expiresAtMillis) {
            return parse(cached.payload, cacheKey);
        }
        if (now < breakerOpenUntilMillis) return Collections.emptyList();

        Payload payload = null;
        TRANSIENT_FAILURE.set(Boolean.FALSE);
        try {
            long directId = directSongId(music);
            if (directId > 0L) {
                payload = fetchLyric(directId, music.getName(), firstArtist(music), albumName(music),
                        (int) (music.getDuration() / 1000L), "songID=" + directId);
            }
            if (payload == null) {
                payload = fetchBySearch(music);
            }
        } catch (Throwable failure) {
            System.err.println("[Music/QQ-QRC] Lyric lookup failed for " + cacheKey + ": " + failure.getMessage());
        }

        boolean transientFailure = Boolean.TRUE.equals(TRANSIENT_FAILURE.get());
        TRANSIENT_FAILURE.remove();

        // 接口抖动时不写负缓存:逐字歌词的优先级必须一直高于官方平台歌词,一次超时不该让这首歌在接下来的
        // 半小时里被永久降级。熔断本身已经防止了连续重试打爆接口。
        if (payload != null || !transientFailure) {
            store(cacheKey, payload);
        }
        return report(music, parse(payload, cacheKey), payload);
    }

    /** 只在真正采用/明确拒绝时各打一行,方便日志里一眼看出这首歌的逐字歌词是从哪来的。 */
    private static List<LyricLine> report(Music music, List<LyricLine> parsed, Payload payload) {
        if (payload == null) return parsed;
        if (!parsed.isEmpty()) {
            System.out.println("[Music/QQ-QRC] " + music.getName() + " matched by " + payload.match
                    + " (" + parsed.size() + " lines)");
        } else {
            System.out.println("[Music/QQ-QRC] " + music.getName() + " matched by " + payload.match
                    + " but the response carries no word timing, keeping line-level lyrics");
        }
        return parsed;
    }

    private static List<LyricLine> parse(Payload payload, String cacheKey) {
        if (payload == null || payload.qrc == null || payload.qrc.trim().isEmpty()) return Collections.emptyList();
        List<LyricLine> parsed = new ArrayList<LyricLine>();
        try {
            LyricParser.parseQrc(payload.qrc, parsed);
        } catch (Throwable failure) {
            System.err.println("[Music/QQ-QRC] QRC parse failed for " + cacheKey + ": " + failure.getMessage());
            return Collections.emptyList();
        }
        if (!LyricParser.hasWordByWordTiming(parsed)) {
            // 只有行级结果:平台兜底链路能给出带翻译的完整 LRC,没必要用这个替代。
            return Collections.emptyList();
        }
        try {
            LyricParser.applyQrcSidecar(payload.translation, parsed, true);
            LyricParser.applyQrcSidecar(payload.romanization, parsed, false);
        } catch (Throwable ignored) {
            // 翻译/罗马音是附加信息,失败不影响主歌词。
        }
        return parsed;
    }

    private static boolean isEnabled() {
        try {
            return MuoniumPlayerExtension.getInstance().musicLyrics.preferQqQrcLyrics.getValue();
        } catch (Throwable ignored) {
            return true;   // 设置尚未初始化时按默认开启处理
        }
    }

    // ── 直接命中 QQ songid ──────────────────────────────────────────────────

    /**
     * 接口要的是数字 songid(不是 songmid)。QQ 源的 sourceId、GD 的 tencent 平台 id / lyric_id 都可能
     * 是数字 songid;拿不到数字就返回 0,交给搜索。
     */
    private static long directSongId(Music music) {
        if (music.isQQ()) {
            long id = numeric(music.getSourceId());
            if (id > 0L) return id;
            return numeric(music.getSourceMid());
        }
        if (music.isGd() && "tencent".equals(safe(music.getGdPlatform()).toLowerCase(Locale.ROOT))) {
            long id = numeric(music.getSourceId());
            if (id > 0L) return id;
            return numeric(music.getGdLyricId());
        }
        return 0L;
    }

    private static long numeric(String value) {
        String trimmed = safe(value).trim();
        if (trimmed.isEmpty() || trimmed.length() > 18) return 0L;
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return 0L;
        }
        try {
            long parsed = Long.parseLong(trimmed);
            return parsed > 0L ? parsed : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    // ── 搜索兜底 ────────────────────────────────────────────────────────────

    private static Payload fetchBySearch(Music music) {
        String title = safe(music.getName()).trim();
        if (title.isEmpty()) return null;
        String artist = firstArtist(music);

        String keyword = artist.isEmpty() ? title : title + ' ' + artist;
        JsonArray candidates = search(keyword);
        if (candidates == null || candidates.size() == 0) {
            String stripped = stripBracketSuffix(title);
            if (!stripped.equals(title)) candidates = search(stripped);
        }
        if (candidates == null || candidates.size() == 0) return null;

        JsonObject best = null;
        int bestScore = 0;
        long bestDurationGap = Long.MAX_VALUE;
        long duration = music.getDuration();
        for (int i = 0; i < candidates.size(); i++) {
            JsonElement element = candidates.get(i);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            int score = score(candidate, title, artist);
            if (score <= 0) continue;

            long gap = Long.MAX_VALUE;
            long interval = intOf(candidate, "interval") * 1000L;
            if (duration > 0L && interval > 0L) gap = Math.abs(duration - interval);
            if (score > bestScore || (score == bestScore && gap < bestDurationGap)) {
                bestScore = score;
                bestDurationGap = gap;
                best = candidate;
            }
        }
        if (best == null) return null;

        long songId = numeric(text(best, "songid"));
        if (songId <= 0L) return null;

        int interval = intOf(best, "interval");
        if (interval <= 0 && duration > 0L) interval = (int) (duration / 1000L);
        return fetchLyric(songId, text(best, "songname"), singerName(best), text(best, "albumname"), interval,
                "search score " + bestScore + " (songID=" + songId + ")");
    }

    private static JsonArray search(String keyword) {
        if (Thread.currentThread().isInterrupted()) return null;
        if (safe(keyword).trim().isEmpty()) return null;
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("w", keyword.trim());
        params.put("format", "json");
        params.put("p", "1");
        params.put("n", String.valueOf(SEARCH_PAGE_SIZE));

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Accept", "application/json");
        headers.put("Referer", SEARCH_REFERER);
        headers.put("User-Agent", MOBILE_USER_AGENT);

        InputStream stream = null;
        try {
            throttle();
            stream = HttpUtils.get(SEARCH_ENDPOINT, params, headers, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
            if (stream == null) {
                noteFailure("empty search response");
                return null;
            }
            String body = HttpUtils.readString(stream);
            noteSuccess();
            JsonObject root = JsonUtils.toJsonObject(body);
            if (root == null) return null;
            JsonObject data = objectOf(root, "data");
            JsonObject song = objectOf(data, "song");
            return arrayOf(song, "list");
        } catch (Throwable failure) {
            noteFailure(failure.getMessage());
            return null;
        } finally {
            closeQuietly(stream);
        }
    }

    /**
     * 候选打分,和 AMLL 那条链路保持同一套判断:3 = 曲名完全一致且歌手对得上,2 = 曲名完全一致且本地
     * 没有歌手信息,1 = 曲名互相包含且歌手对得上,0 = 拒绝。
     */
    private static int score(JsonObject candidate, String title, String artist) {
        String normalizedTitle = normalize(title);
        if (normalizedTitle.isEmpty()) return 0;

        String candidateTitle = normalize(text(candidate, "songname"));
        if (candidateTitle.isEmpty()) return 0;

        boolean exactTitle = candidateTitle.equals(normalizedTitle);
        boolean looseTitle = !exactTitle
                && (candidateTitle.contains(normalizedTitle) || normalizedTitle.contains(candidateTitle));
        if (!exactTitle && !looseTitle) return 0;

        String normalizedArtist = normalize(artist);
        if (normalizedArtist.isEmpty()) return exactTitle ? 2 : 0;

        for (String name : singerNames(candidate)) {
            String normalized = normalize(name);
            if (normalized.isEmpty()) continue;
            if (normalized.equals(normalizedArtist)
                    || normalized.contains(normalizedArtist) || normalizedArtist.contains(normalized)) {
                return exactTitle ? 3 : 1;
            }
        }
        return 0;
    }

    private static List<String> singerNames(JsonObject candidate) {
        List<String> names = new ArrayList<String>();
        try {
            JsonArray singers = arrayOf(candidate, "singer");
            if (singers == null) return names;
            for (int i = 0; i < singers.size(); i++) {
                JsonElement element = singers.get(i);
                if (element == null || !element.isJsonObject()) continue;
                String name = text(element.getAsJsonObject(), "name");
                if (name != null && !name.trim().isEmpty()) names.add(name.trim());
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private static String singerName(JsonObject candidate) {
        List<String> names = singerNames(candidate);
        return names.isEmpty() ? "" : names.get(0);
    }

    // ── 歌词请求 ────────────────────────────────────────────────────────────

    /**
     * {@code music.musichallSong.PlayLyricInfo}。注意 {@code crypt=1} 不是可选的:服务端无论怎么传都
     * 返回密文,所以固定按密文处理。整个请求不需要 cookie 或登录态。
     */
    private static Payload fetchLyric(long songId, String songName, String singerName, String albumName,
                                      int intervalSeconds, String matchDescription) {
        if (songId <= 0L) return null;
        if (Thread.currentThread().isInterrupted()) return null;

        JsonObject param = new JsonObject();
        param.addProperty("albumName", base64(albumName));
        param.addProperty("crypt", 1);
        param.addProperty("ct", 19);
        param.addProperty("cv", 2111);
        param.addProperty("interval", Math.max(0, intervalSeconds));
        param.addProperty("lrc_t", 0);
        param.addProperty("qrc", 1);
        param.addProperty("qrc_t", 0);
        param.addProperty("roma", 1);
        param.addProperty("roma_t", 0);
        param.addProperty("singerName", base64(singerName));
        param.addProperty("songID", songId);
        param.addProperty("songName", base64(songName));
        param.addProperty("trans", 1);
        param.addProperty("trans_t", 0);
        param.addProperty("type", 0);

        JsonObject comm = new JsonObject();
        comm.addProperty("ct", 11);
        comm.addProperty("cv", "1003006");
        comm.addProperty("v", "1003006");
        comm.addProperty("os_ver", "15");
        comm.addProperty("phonetype", "24122RKC7C");
        comm.addProperty("tmeAppID", "qqmusiclight");
        comm.addProperty("nettype", "NETWORK_WIFI");
        comm.addProperty("udid", "0");
        comm.addProperty("uid", "0");
        comm.addProperty("sid", "");
        comm.addProperty("loginUin", "0");
        comm.addProperty("platform", "yqq.json");
        comm.addProperty("needNewCode", 0);

        JsonObject request = new JsonObject();
        request.addProperty("method", "GetPlayLyricInfo");
        request.addProperty("module", "music.musichallSong.PlayLyricInfo");
        request.add("param", param);

        JsonObject body = new JsonObject();
        body.add("comm", comm);
        body.add("request", request);

        Map<String, String> headers = new HashMap<String, String>();
        // HttpUtils 会忽略 mediaType 参数,Content-Type 必须自己写进请求头。
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Referer", LYRIC_REFERER);
        headers.put("User-Agent", DESKTOP_USER_AGENT);

        InputStream stream = null;
        try {
            throttle();
            // 请求体只含 ASCII(歌曲信息已 base64),HttpUtils 用平台默认编码写出也不会损坏。
            stream = HttpUtils.request(LYRIC_ENDPOINT, body.toString(), headers, "POST",
                    "application/json", CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
            if (stream == null) {
                noteFailure("empty lyric response");
                return null;
            }
            String response = HttpUtils.readString(stream);
            noteSuccess();

            JsonObject root = JsonUtils.toJsonObject(response);
            if (root == null) return null;
            JsonObject data = objectOf(objectOf(root, "request"), "data");
            if (data == null) return null;

            String qrc = decryptSection(text(data, "lyric"));
            if (qrc == null) return null;
            return new Payload(qrc, decryptSection(text(data, "trans")), decryptSection(text(data, "roma")),
                    matchDescription);
        } catch (Throwable failure) {
            noteFailure(failure.getMessage());
            return null;
        } finally {
            closeQuietly(stream);
        }
    }

    /** 解密一段并取出真正的歌词正文;翻译/罗马音本身就是纯文本,没有 XML 外壳。 */
    private static String decryptSection(String hex) {
        String decrypted = QrcCipher.decryptHex(hex);
        if (decrypted == null) return null;
        String content = extractLyricContent(decrypted);
        return content == null || content.trim().isEmpty() ? null : content;
    }

    /**
     * QRC 正文放在 {@code <Lyric_1 LyricType="1" LyricContent="..."/>} 的属性里,而属性值里含有原始
     * 换行,用 XML 解析器读会被规范化掉甚至直接报错,所以按字符串切。收尾优先找 {@code "/>},歌词里出现
     * 未转义的引号时不会被截断。
     */
    private static String extractLyricContent(String decrypted) {
        String trimmed = decrypted.trim();
        if (!trimmed.startsWith("<")) return trimmed;

        int marker = trimmed.indexOf(LYRIC_CONTENT_MARKER);
        if (marker < 0) return null;
        int start = marker + LYRIC_CONTENT_MARKER.length();
        int end = trimmed.indexOf("\"/>", start);
        if (end < 0) end = trimmed.indexOf("\"></", start);
        if (end < 0) end = trimmed.indexOf('"', start);
        if (end < start) return null;
        return unescapeXml(trimmed.substring(start, end));
    }

    private static String unescapeXml(String value) {
        if (value == null || value.indexOf('&') < 0) return value;
        return value.replace("&#10;", "\n")
                .replace("&#13;", "\r")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(safe(value).getBytes(StandardCharsets.UTF_8));
    }

    // ── 限流 / 熔断 ─────────────────────────────────────────────────────────

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
        // 让调用方知道这次"没取到"是接口不可用,而不是 QQ 没有这首歌的逐字歌词。
        TRANSIENT_FAILURE.set(Boolean.TRUE);
        if (CONSECUTIVE_FAILURES.incrementAndGet() >= BREAKER_FAILURE_THRESHOLD) {
            CONSECUTIVE_FAILURES.set(0);
            breakerOpenUntilMillis = System.currentTimeMillis() + BREAKER_COOLDOWN_MILLIS;
            System.err.println("[Music/QQ-QRC] Lyric API unreachable (" + reason + "), pausing lookups for "
                    + (BREAKER_COOLDOWN_MILLIS / 60_000L) + " minutes");
        }
    }

    // ── 缓存 ───────────────────────────────────────────────────────────────

    private static void store(String cacheKey, Payload payload) {
        long expiry = System.currentTimeMillis() + (payload == null ? MISS_CACHE_MILLIS : HIT_CACHE_MILLIS);
        if (CACHE.size() >= CACHE_CAPACITY) evictOldest();
        CACHE.put(cacheKey, new CacheEntry(payload, expiry));
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

    private static String albumName(Music music) {
        try {
            return music.getAlbum() == null ? "" : safe(music.getAlbum().getName());
        } catch (Throwable ignored) {
            return "";
        }
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

    /** JsonUtils 的取值方法在字段缺失时会抛异常,这里统一按"缺失即空"处理。 */
    private static String text(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
            return object.get(property).getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int intOf(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0;
            return object.get(property).getAsInt();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static JsonObject objectOf(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || !object.get(property).isJsonObject()) return null;
            return object.getAsJsonObject(property);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonArray arrayOf(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || !object.get(property).isJsonArray()) return null;
            return object.getAsJsonArray(property);
        } catch (Throwable ignored) {
            return null;
        }
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

    /** 一首歌解密后的三段文本。缓存这个而不是 LyricLine:渲染对象带有滚动/透明度状态。 */
    private static final class Payload {
        private final String qrc;
        private final String translation;
        private final String romanization;
        /** 命中方式,只用于日志。 */
        private final String match;

        private Payload(String qrc, String translation, String romanization, String match) {
            this.qrc = qrc;
            this.translation = translation;
            this.romanization = romanization;
            this.match = match;
        }
    }

    private static final class CacheEntry {
        /** null 表示已知未命中。 */
        private final Payload payload;
        private final long expiresAtMillis;

        private CacheEntry(Payload payload, long expiresAtMillis) {
            this.payload = payload;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
