package com.muoniumplayer.core.ncm.music;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in client for the GD Studio aggregated music API (https://music-api.gdstudio.xyz).
 *
 * <p>Only the four documented request types are used: {@code types=search}, {@code types=url},
 * {@code types=pic} and {@code types=lyric}. Every request carries the platform and identifiers
 * returned by the preceding search response, exactly as the documentation requires.</p>
 *
 * <h3>Fallback protection</h3>
 * <ul>
 *   <li>A rolling-window limiter caps traffic at the documented 50 requests / 5 minutes and keeps a
 *       reserve so cosmetic artwork/lyric traffic can never starve search and playback.</li>
 *   <li>Per-request-type timeouts stop a slow artwork provider from blocking a playback resolve.</li>
 *   <li>Per-source health tracking (circuit breaker) records the upstream error, cools a failing
 *       source down and recovers automatically as soon as one request succeeds.</li>
 *   <li>Playback resolves walk a documented bitrate ladder and, when the selected source still
 *       returns nothing, re-match the track on one healthy source instead of failing outright.</li>
 *   <li>Artwork/lyric misses are negatively cached so a dead provider is not polled every frame.</li>
 * </ul>
 */
public final class GdStudioMusicService {

    public static final String API_ENDPOINT = "https://music-api.gdstudio.xyz/api.php";

    private static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEARCH_COUNT = 20;

    private static final long REQUEST_WINDOW_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_REQUESTS_PER_WINDOW = 50;
    /** Slots kept for search/playback; artwork and lyrics stop requesting below this reserve. */
    private static final int COSMETIC_BUDGET_RESERVE = 12;
    /** A bitrate downgrade retry only runs while this much budget is still free. */
    private static final int LADDER_MIN_BUDGET = 6;
    /** A cross-source rescue costs one search plus one url request. */
    private static final int CROSS_SOURCE_MIN_BUDGET = 10;
    private static final int CROSS_SOURCE_LIMIT = 1;
    /** A lyric rescue is decoration; it only runs while playback has ample budget to spare. */
    private static final int LYRIC_FALLBACK_MIN_BUDGET = 20;
    /** Wall-clock guards so a slow provider cannot chain three full read timeouts. */
    private static final long LADDER_DEADLINE_MILLIS = 18_000L;
    private static final long CROSS_SOURCE_DEADLINE_MILLIS = 22_000L;

    private static final long SEARCH_CACHE_MILLIS = 15_000L;
    private static final long COVER_CACHE_MILLIS = 6L * 60L * 60L * 1000L;
    private static final long LYRIC_CACHE_MILLIS = 6L * 60L * 60L * 1000L;
    /** Empty artwork/lyric answers are remembered briefly instead of being re-requested. */
    private static final long NEGATIVE_CACHE_MILLIS = 10L * 60L * 1000L;

    private static final long UNSUPPORTED_COOLDOWN_MILLIS = 30L * 60L * 1000L;
    private static final long EMPTY_PLAYBACK_COOLDOWN_MILLIS = 60L * 1000L;
    private static final long[] FAILURE_COOLDOWN_MILLIS = {10_000L, 45_000L, 180_000L, 600_000L};

    private static final Object REQUEST_LOCK = new Object();
    private static final Deque<Long> REQUEST_TIMESTAMPS = new ArrayDeque<>();
    private static final Object CACHE_LOCK = new Object();
    private static final Map<String, CacheEntry<List<GdTrack>>> SEARCH_CACHE = new LinkedHashMap<>();
    private static final Map<String, CacheEntry<String>> COVER_CACHE = new LinkedHashMap<>();
    private static final Map<String, CacheEntry<Tuple<String, String>>> LYRIC_CACHE = new LinkedHashMap<>();
    private static final Map<String, CacheEntry<Tuple<String, String>>> LYRIC_FALLBACK_CACHE = new LinkedHashMap<>();
    private static final Object HEALTH_LOCK = new Object();
    private static final Map<String, SourceHealth> HEALTH = new LinkedHashMap<>();
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

    /** Upstream error carrying the documented {@code detail} message. */
    public static final class GdApiException extends RuntimeException {
        public final int httpStatus;
        public final boolean unsupportedSource;

        private GdApiException(String message, int httpStatus, boolean unsupportedSource) {
            super(message);
            this.httpStatus = httpStatus;
            this.unsupportedSource = unsupportedSource;
        }
    }

    /** Result of a GD Studio playback resolution, including which source actually served it. */
    public static final class ResolveResult {
        public final String url;
        public final String format;
        public final String platform;
        public final String requestedPlatform;
        public final int bitrate;

        private ResolveResult(String url, String format, String platform, String requestedPlatform, int bitrate) {
            this.url = url;
            this.format = format;
            this.platform = platform;
            this.requestedPlatform = requestedPlatform;
            this.bitrate = bitrate;
        }

        /** True when the selected source failed and another healthy source rescued playback. */
        public boolean isFallback() {
            return !platform.isEmpty() && !requestedPlatform.isEmpty() && !platform.equals(requestedPlatform);
        }
    }

    /** Search results plus the source that actually answered, so the UI can explain a fallback. */
    public static final class SearchOutcome {
        public final List<GdTrack> tracks;
        public final String source;
        public final String requestedSource;
        public final String failureReason;
        /** True when the selected source errored out; false when it simply had no match. */
        public final boolean requestedSourceFailed;

        private SearchOutcome(List<GdTrack> tracks, String source, String requestedSource,
                              String failureReason, boolean requestedSourceFailed) {
            this.tracks = tracks == null ? Collections.<GdTrack>emptyList() : tracks;
            this.source = safe(source);
            this.requestedSource = safe(requestedSource);
            this.failureReason = safe(failureReason);
            this.requestedSourceFailed = requestedSourceFailed;
        }

        public boolean isFallback() {
            return !source.isEmpty() && !requestedSource.isEmpty() && !source.equals(requestedSource);
        }

        public boolean isEmpty() {
            return tracks.isEmpty();
        }
    }

    /** Runtime health of one source, used by the source menu and by the fallback picker. */
    public static final class Health {
        public final boolean cooling;
        public final boolean unsupported;
        public final long cooldownRemainingMillis;
        public final String lastError;

        private Health(boolean cooling, boolean unsupported, long cooldownRemainingMillis, String lastError) {
            this.cooling = cooling;
            this.unsupported = unsupported;
            this.cooldownRemainingMillis = cooldownRemainingMillis;
            this.lastError = safe(lastError);
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

    /** Requests still available inside the documented five-minute window. */
    public static int remainingRequestBudget() {
        synchronized (REQUEST_LOCK) {
            pruneTimestampsLocked(System.currentTimeMillis());
            return Math.max(0, MAX_REQUESTS_PER_WINDOW - REQUEST_TIMESTAMPS.size());
        }
    }

    /** Runtime health snapshot; never null. */
    public static Health getHealth(String key) {
        String platform = safe(key).toLowerCase(Locale.ROOT);
        synchronized (HEALTH_LOCK) {
            SourceHealth health = HEALTH.get(platform);
            if (health == null) return new Health(false, false, 0L, "");
            long now = System.currentTimeMillis();
            long httpRemaining = Math.max(0L, health.cooldownUntil - now);
            long playbackRemaining = Math.max(0L, health.playbackCooldownUntil - now);
            long remaining = Math.max(httpRemaining, playbackRemaining);
            String lastError = httpRemaining >= playbackRemaining && !health.lastError.isEmpty()
                    ? health.lastError
                    : (health.playbackError.isEmpty() ? health.lastError : health.playbackError);
            return new Health(remaining > 0L, health.unsupported && httpRemaining > 0L, remaining, lastError);
        }
    }

    /** Short status suffix for the source menu, empty when the source looks healthy. */
    public static String statusLabel(String key) {
        Health health = getHealth(key);
        if (!health.cooling) return "";
        long seconds = (health.cooldownRemainingMillis + 999L) / 1000L;
        String window = seconds >= 60L ? (seconds / 60L) + " 分钟" : seconds + " 秒";
        return (health.unsupported ? "接口未开放 · " : "暂时故障 · ") + window + "后重试";
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
        JsonElement root = json(query.toString(), RequestKind.SEARCH, platform);
        if (root == null) return Collections.emptyList();
        if (root.isJsonObject()) {
            String reason = firstError(root.getAsJsonObject());
            markFailure(platform, reason, false);
            throw new IllegalStateException(reason);
        }

        List<GdTrack> result = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                // Some sources ignore the requested page size (JOOX answers 30 for count=5),
                // so the payload is trimmed locally to keep list rendering predictable.
                if (result.size() >= pageSize) break;
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
        // Never pin an empty answer: some sources intermittently return an empty array, and a cached
        // miss would make an immediate retry (or the user searching again) pointless for 15 seconds.
        if (!immutable.isEmpty()) {
            cacheSearch(cacheKey, immutable);
        }
        return new ArrayList<>(immutable);
    }

    /**
     * Search with fallback protection: the selected source is always tried first, and only a real
     * failure (or a documented "source not supported" answer) hands the query to one healthy
     * source so the search screen still shows results instead of an empty list.
     */
    public static SearchOutcome searchWithFallback(String source, String keyword, int count, int page) {
        String requested = normalizePlatform(source);
        String name = safe(keyword);
        if (requested.isEmpty() || name.isEmpty()) {
            return new SearchOutcome(Collections.<GdTrack>emptyList(), "", requested,
                    requested.isEmpty() ? "未选择有效的 GD音乐台 平台" : "", false);
        }

        String reason = "";
        boolean failed = false;
        if (!isHardBlocked(requested)) {
            try {
                List<GdTrack> tracks = search(requested, name, count, page);
                if (tracks.isEmpty() && isFlakyEmptySource(requested)
                        && remainingRequestBudget() >= LADDER_MIN_BUDGET) {
                    // Bilibili answers HTTP 200 with an empty array at random, so the source the user
                    // actually selected gets one more chance before the query leaves it.
                    tracks = search(requested, name, count, page);
                }
                if (!tracks.isEmpty()) {
                    return new SearchOutcome(tracks, requested, requested, "", false);
                }
                // An empty array is a legitimate answer, not an outage: some sources also return
                // an empty array intermittently, which is why the query is still offered elsewhere.
                reason = "该音源没有匹配结果";
            } catch (Throwable throwable) {
                reason = describe(throwable);
                failed = true;
            }
        } else {
            reason = statusLabel(requested);
            if (reason.isEmpty()) reason = "该音源暂不可用";
            failed = true;
        }

        for (String candidate : fallbackCandidates(requested)) {
            if (isCancelled()) break;
            if (remainingRequestBudget() < LADDER_MIN_BUDGET) break;
            try {
                List<GdTrack> tracks = search(candidate, name, count, page);
                if (!tracks.isEmpty()) {
                    return new SearchOutcome(tracks, candidate, requested, reason, failed);
                }
            } catch (Throwable ignored) {
                // Keep the primary failure as the user-facing reason.
            }
        }
        return new SearchOutcome(Collections.<GdTrack>emptyList(), "", requested, reason, failed);
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
        JsonElement root = json(query.toString(), RequestKind.URL, platform);
        return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    /**
     * GET types=pic with only the documented 300/500 size values.
     *
     * <p>Artwork is optional decoration: a missing budget, a failing provider or an empty answer
     * returns an empty string instead of throwing, and misses are negatively cached so the cover
     * loader cannot poll a dead provider on every list frame.</p>
     */
    public static String getPicUrl(String source, String picId, int size) {
        String platform = normalizePlatform(source);
        String id = safe(picId);
        if (platform.isEmpty() || id.isEmpty()) return "";
        // Bilibili already returns a usable (protocol-relative) artwork URL in pic_id,
        // so no request is spent on resolving it again.
        String direct = directImageUrl(id);
        if (!direct.isEmpty()) return direct;

        int imageSize = size == 500 ? 500 : 300;
        String cacheKey = platform + ':' + id + ':' + imageSize;
        String cached = cached(COVER_CACHE, cacheKey);
        if (cached != null) return cached;
        if (!hasCosmeticBudget() || isHardBlocked(platform)) return "";

        String url = "";
        try {
            StringBuilder query = new StringBuilder("types=pic");
            append(query, "source", platform);
            append(query, "id", id);
            append(query, "size", String.valueOf(imageSize));
            JsonElement root = json(query.toString(), RequestKind.PIC, platform);
            url = root != null && root.isJsonObject() ? text(root.getAsJsonObject(), "url") : "";
        } catch (Throwable throwable) {
            cache(COVER_CACHE, cacheKey, "", NEGATIVE_CACHE_MILLIS);
            return "";
        }
        cache(COVER_CACHE, cacheKey, url, url.isEmpty() ? NEGATIVE_CACHE_MILLIS : COVER_CACHE_MILLIS);
        return url;
    }

    /**
     * GET types=lyric: original LRC plus optional translated LRC.
     *
     * <p>Lyrics are optional too. Empty answers (Bilibili never has any) are cached briefly so the
     * lyric panel does not re-request them, and failures return {@code null} without throwing.</p>
     */
    public static Tuple<String, String> getLyric(String source, String lyricId) {
        String platform = normalizePlatform(source);
        String id = safe(lyricId);
        if (platform.isEmpty() || id.isEmpty()) return null;
        String cacheKey = platform + ':' + id;
        Tuple<String, String> cached = cached(LYRIC_CACHE, cacheKey);
        if (cached != null) return cached;
        if (!hasCosmeticBudget() || isHardBlocked(platform)) return null;

        try {
            StringBuilder query = new StringBuilder("types=lyric");
            append(query, "source", platform);
            append(query, "id", id);
            JsonElement root = json(query.toString(), RequestKind.LYRIC, platform);
            if (root == null || !root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();
            Tuple<String, String> lyric = new Tuple<>(text(object, "lyric"), text(object, "tlyric"));
            boolean empty = lyric.getA().isEmpty() && lyric.getB().isEmpty();
            cache(LYRIC_CACHE, cacheKey, lyric, empty ? NEGATIVE_CACHE_MILLIS : LYRIC_CACHE_MILLIS);
            return lyric;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * Lyrics with fallback protection. Bilibili never returns an LRC, and other sources occasionally
     * have none, so a healthy source is matched by title/artist once and the answer (hit or miss) is
     * cached. The rescue only runs while there is plenty of request budget left, because it costs a
     * search plus a lyric request and must never compete with playback.
     */
    public static Tuple<String, String> getLyricWithFallback(String source, String lyricId,
                                                            String trackName, String artist) {
        Tuple<String, String> primary = getLyric(source, lyricId);
        if (primary != null && !safe(primary.getA()).isEmpty()) return primary;

        String requested = normalizePlatform(source);
        String title = safe(trackName);
        if (requested.isEmpty() || title.isEmpty()) return primary;

        String cacheKey = requested + ':' + safe(lyricId) + ':' + title.toLowerCase(Locale.ROOT);
        Tuple<String, String> cached = cached(LYRIC_FALLBACK_CACHE, cacheKey);
        if (cached != null) {
            return safe(cached.getA()).isEmpty() ? primary : cached;
        }
        if (remainingRequestBudget() < LYRIC_FALLBACK_MIN_BUDGET) return primary;

        int attempts = 0;
        for (String candidate : fallbackCandidates(requested)) {
            if (isCancelled()) break;
            if (attempts >= CROSS_SOURCE_LIMIT) break;
            attempts++;
            try {
                List<GdTrack> tracks = search(candidate, searchQuery(title, artist), 10, 1);
                GdTrack match = bestMatch(tracks, title, artist);
                if (match == null || safe(match.lyricId).isEmpty()) continue;
                Tuple<String, String> rescued = getLyric(candidate, match.lyricId);
                if (rescued != null && !safe(rescued.getA()).isEmpty()) {
                    cache(LYRIC_FALLBACK_CACHE, cacheKey, rescued, LYRIC_CACHE_MILLIS);
                    return rescued;
                }
            } catch (Throwable ignored) {
                // Lyrics are decoration: a failed rescue must never surface as an error.
            }
        }
        // Remember the miss so the lyric panel does not retry it on every playback.
        cache(LYRIC_FALLBACK_CACHE, cacheKey, new Tuple<>("", ""), NEGATIVE_CACHE_MILLIS);
        return primary;
    }

    /**
     * Resolves exactly the selected source/id pair from the search response, walking the documented
     * bitrate ladder when a source answers with an empty URL for the preferred tier.
     */
    public static ResolveResult resolveTrack(String source, String trackId, Quality quality) throws Exception {
        return resolveDirect(requirePlatform(source), safe(trackId), quality);
    }

    /**
     * Playback resolve with full fallback protection: documented bitrate ladder on the selected
     * source first, then one healthy recommended source re-matched by title/artist. Returns
     * {@code null} only when every protected attempt legitimately produced no stream.
     */
    public static ResolveResult resolveTrackWithFallback(String source, String trackId, String trackName,
                                                         String artist, Quality quality) throws Exception {
        return resolveProtected(source, trackId, trackName, artist, quality, false);
    }

    /**
     * Skips the selected source entirely and only tries a healthy recommended source. Used when the
     * selected source did return a URL but the downloaded bytes turned out not to be playable audio.
     */
    public static ResolveResult resolveCrossSource(String source, String trackName, String artist,
                                                  Quality quality) throws Exception {
        return resolveProtected(source, "", trackName, artist, quality, true);
    }

    private static ResolveResult resolveProtected(String source, String trackId, String trackName,
                                                  String artist, Quality quality,
                                                  boolean skipSelectedSource) throws Exception {
        String requested = requirePlatform(source);
        String id = safe(trackId);
        long started = System.currentTimeMillis();
        Exception primaryError = null;
        if (!skipSelectedSource && !isHardBlocked(requested)) {
            try {
                ResolveResult direct = resolveDirect(requested, id, quality);
                if (direct != null) return direct;
            } catch (Exception error) {
                primaryError = error;
            }
        }

        String title = safe(trackName);
        if (!title.isEmpty()) {
            int attempts = 0;
            for (String candidate : fallbackCandidates(requested)) {
                if (isCancelled()) break;
                if (attempts >= CROSS_SOURCE_LIMIT) break;
                if (remainingRequestBudget() < CROSS_SOURCE_MIN_BUDGET) break;
                if (System.currentTimeMillis() - started > CROSS_SOURCE_DEADLINE_MILLIS) break;
                attempts++;
                try {
                    List<GdTrack> tracks = search(candidate, searchQuery(title, artist), 10, 1);
                    GdTrack match = bestMatch(tracks, title, artist);
                    if (match == null) continue;
                    ResolveResult rescued = resolveDirect(candidate, match.id, quality);
                    if (rescued != null) {
                        return new ResolveResult(rescued.url, rescued.format, candidate, requested, rescued.bitrate);
                    }
                } catch (Throwable ignored) {
                    // A failed rescue must never mask the original error.
                }
            }
        }
        if (primaryError != null) throw primaryError;
        return null;
    }

    private static ResolveResult resolveDirect(String platform, String trackId, Quality quality) throws Exception {
        String id = safe(trackId);
        if (id.isEmpty()) return null;
        int[] ladder = bitrateLadder(quality);
        long ladderStarted = System.currentTimeMillis();
        Exception firstError = null;
        for (int index = 0; index < ladder.length; index++) {
            if (isCancelled()) break;
            if (index > 0 && remainingRequestBudget() < LADDER_MIN_BUDGET) break;
            if (index > 0 && System.currentTimeMillis() - ladderStarted > LADDER_DEADLINE_MILLIS) break;
            try {
                JsonObject object = requestUrl(platform, id, ladder[index]);
                String url = object == null ? "" : text(object, "url");
                if (!url.isEmpty()) {
                    markPlaybackSuccess(platform);
                    return new ResolveResult(url, inferFormat(url), platform, platform,
                            intValue(object, "br", ladder[index]));
                }
            } catch (GdApiException error) {
                // A source the upstream service does not expose will not answer another bitrate.
                if (error.unsupportedSource) throw error;
                firstError = error;
            } catch (Exception error) {
                firstError = error;
            }
        }
        if (firstError != null) throw firstError;
        // A cancelled attempt says nothing about the source, so it must not be flagged as broken.
        ensureNotCancelled();
        markEmptyPlayback(platform);
        return null;
    }

    /** Recommended, currently healthy sources that may rescue a failing selection. */
    private static List<String> fallbackCandidates(String requested) {
        List<String> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(safe(requested).toLowerCase(Locale.ROOT));
        for (Platform platform : PLATFORMS) {
            if (platform.status != PlatformStatus.RECOMMENDED) continue;
            if (!seen.add(platform.key)) continue;
            if (isCooling(platform.key)) continue;
            candidates.add(platform.key);
        }
        return candidates;
    }

    /** Sources observed to answer HTTP 200 with an empty array even for a matching query. */
    private static boolean isFlakyEmptySource(String source) {
        return "bilibili".equals(safe(source).toLowerCase(Locale.ROOT));
    }

    private static String searchQuery(String title, String artist) {
        String name = safe(title);
        String performer = safe(artist);
        return performer.isEmpty() ? name : name + ' ' + performer;
    }

    /** Picks the closest search hit; simplified/traditional spellings still match by character overlap. */
    private static GdTrack bestMatch(List<GdTrack> tracks, String title, String artist) {
        if (tracks == null || tracks.isEmpty()) return null;
        GdTrack best = null;
        double bestScore = 0.0;
        boolean artistKnown = !safe(artist).isEmpty();
        for (GdTrack track : tracks) {
            if (track == null || safe(track.id).isEmpty()) continue;
            double nameScore = titleSimilarity(track.name, title);
            if (nameScore < .5) continue;
            double artistScore = similarity(track.artist, artist);
            // Without an artist guard a rescue could silently play a different song that merely
            // shares characters with the requested title.
            if (artistKnown && !safe(track.artist).isEmpty() && artistScore < .34) continue;
            double score = nameScore * 2.0 + artistScore;
            if (score > bestScore) {
                bestScore = score;
                best = track;
            }
        }
        return best;
    }

    /** Bigram overlap: order-sensitive enough that "晴天" and "天晴" are no longer treated as equal. */
    private static double titleSimilarity(String left, String right) {
        String a = normalizeForMatch(left);
        String b = normalizeForMatch(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        if (a.length() < 2 || b.length() < 2) return similarity(a, b);
        Set<String> first = bigrams(a);
        Set<String> second = bigrams(b);
        int shared = 0;
        for (String value : first) {
            if (second.contains(value)) shared++;
        }
        int union = first.size() + second.size() - shared;
        return union <= 0 ? 0.0 : (double) shared / (double) union;
    }

    private static Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < value.length(); index++) {
            result.add(value.substring(index, index + 2));
        }
        return result;
    }

    private static String normalizeForMatch(String value) {
        StringBuilder builder = new StringBuilder();
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isLetterOrDigit(character)) builder.append(character);
        }
        return builder.toString();
    }

    private static double similarity(String left, String right) {
        Set<Character> a = characters(left);
        Set<Character> b = characters(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int shared = 0;
        for (Character value : a) {
            if (b.contains(value)) shared++;
        }
        int union = a.size() + b.size() - shared;
        return union <= 0 ? 0.0 : (double) shared / (double) union;
    }

    private static Set<Character> characters(String value) {
        Set<Character> result = new LinkedHashSet<>();
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isLetterOrDigit(character)) result.add(character);
        }
        return result;
    }

    private static String directImageUrl(String picId) {
        String value = safe(picId);
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("https://")) return value;
        if (value.startsWith("http://")) return "https://" + value.substring("http://".length());
        return "";
    }

    /** Documented bitrate ladder: preferred tier first, then progressively safer tiers. */
    private static int[] bitrateLadder(Quality quality) {
        Quality requested = quality == null ? Quality.LOSSLESS : quality;
        switch (requested) {
            case STANDARD:
                return new int[]{128, 320};
            case HIGHER:
                return new int[]{192, 320, 128};
            case EXHIGH:
                return new int[]{320, 192, 128};
            case LOSSLESS:
            default:
                return new int[]{999, 320, 128};
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

    /** Request classes with their own timeouts; artwork/lyrics must never stall a playback resolve. */
    private enum RequestKind {
        SEARCH(12_000, 25_000, false),
        URL(12_000, 30_000, false),
        PIC(6_000, 10_000, true),
        LYRIC(6_000, 10_000, true);

        private final int connectTimeout;
        private final int readTimeout;
        private final boolean cosmetic;

        RequestKind(int connectTimeout, int readTimeout, boolean cosmetic) {
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.cosmetic = cosmetic;
        }
    }

    private static void ensureNotCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("GD音乐台请求已取消");
        }
    }

    private static boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }

    private static boolean hasCosmeticBudget() {
        return remainingRequestBudget() > COSMETIC_BUDGET_RESERVE;
    }

    private static void pruneTimestampsLocked(long now) {
        long cutoff = now - REQUEST_WINDOW_MILLIS;
        while (!REQUEST_TIMESTAMPS.isEmpty() && REQUEST_TIMESTAMPS.peekFirst() <= cutoff) {
            REQUEST_TIMESTAMPS.removeFirst();
        }
    }

    /** Enforces GD Studio's documented rolling request budget before opening a connection. */
    private static void acquireRequestSlot(RequestKind kind) {
        synchronized (REQUEST_LOCK) {
            long now = System.currentTimeMillis();
            pruneTimestampsLocked(now);
            int limit = kind.cosmetic
                    ? MAX_REQUESTS_PER_WINDOW - COSMETIC_BUDGET_RESERVE
                    : MAX_REQUESTS_PER_WINDOW;
            if (REQUEST_TIMESTAMPS.size() >= limit) {
                long retryAfter = REQUEST_TIMESTAMPS.isEmpty() ? 1_000L
                        : Math.max(1L, REQUEST_TIMESTAMPS.peekFirst() + REQUEST_WINDOW_MILLIS - now);
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

    private static final class SourceHealth {
        private int consecutiveFailures;
        private long cooldownUntil;
        private boolean unsupported;
        private String lastError = "";
        // A source can answer HTTP 200 for search yet never hand out a stream (kuwo). That state
        // must survive a successful search, otherwise the menu would claim the source is healthy.
        private long playbackCooldownUntil;
        private String playbackError = "";
    }

    /** True only for sources the upstream service reported as not supported, while still cooling. */
    private static boolean isHardBlocked(String source) {
        Health health = getHealth(source);
        return health.unsupported && health.cooling;
    }

    private static boolean isCooling(String source) {
        return getHealth(source).cooling;
    }

    /** Clears transport-level failures. A known playback outage is kept until a stream succeeds. */
    private static void markSuccess(String source) {
        String platform = safe(source).toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) return;
        synchronized (HEALTH_LOCK) {
            SourceHealth health = HEALTH.get(platform);
            if (health == null) return;
            health.consecutiveFailures = 0;
            health.cooldownUntil = 0L;
            health.unsupported = false;
            health.lastError = "";
            if (health.playbackCooldownUntil <= System.currentTimeMillis()) {
                HEALTH.remove(platform);
            }
        }
    }

    /** A real stream came back: the source is fully healthy again. */
    private static void markPlaybackSuccess(String source) {
        String platform = safe(source).toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) return;
        synchronized (HEALTH_LOCK) {
            HEALTH.remove(platform);
        }
    }

    private static void markFailure(String source, String reason, boolean unsupported) {
        String platform = safe(source).toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) return;
        synchronized (HEALTH_LOCK) {
            SourceHealth health = HEALTH.get(platform);
            if (health == null) {
                health = new SourceHealth();
                HEALTH.put(platform, health);
            }
            health.lastError = safe(reason);
            health.unsupported = unsupported;
            if (unsupported) {
                health.consecutiveFailures = FAILURE_COOLDOWN_MILLIS.length;
                health.cooldownUntil = System.currentTimeMillis() + UNSUPPORTED_COOLDOWN_MILLIS;
                return;
            }
            health.consecutiveFailures = Math.min(FAILURE_COOLDOWN_MILLIS.length, health.consecutiveFailures + 1);
            health.cooldownUntil = System.currentTimeMillis()
                    + FAILURE_COOLDOWN_MILLIS[health.consecutiveFailures - 1];
        }
    }

    /** A documented 200 answer with an empty URL: the source lives, but playback is unavailable. */
    private static void markEmptyPlayback(String source) {
        String platform = safe(source).toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) return;
        synchronized (HEALTH_LOCK) {
            SourceHealth health = HEALTH.get(platform);
            if (health == null) {
                health = new SourceHealth();
                HEALTH.put(platform, health);
            }
            health.playbackError = "该音源未返回可播放链接";
            health.playbackCooldownUntil = Math.max(health.playbackCooldownUntil,
                    System.currentTimeMillis() + EMPTY_PLAYBACK_COOLDOWN_MILLIS);
        }
    }

    private static JsonElement json(String query, RequestKind kind, String source) throws Exception {
        // A resolve abandoned by its caller (timeout cancels the future with an interrupt) must not
        // keep consuming the documented 50 requests / 5 minutes for a result nobody will read.
        ensureNotCancelled();
        acquireRequestSlot(kind);
        HttpURLConnection connection = (HttpURLConnection) new URL(API_ENDPOINT + "?" + query).openConnection();
        connection.setConnectTimeout(kind.connectTimeout);
        connection.setReadTimeout(kind.readTimeout);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("User-Agent", AGENT);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String detail = errorDetail(connection);
                boolean unsupported = isUnsupportedSourceDetail(detail);
                String message = detail.isEmpty()
                        ? "GD音乐台返回 HTTP " + status
                        : "GD音乐台 " + status + "：" + detail;
                markFailure(source, unsupported ? "接口未开放该音源" : message, unsupported);
                throw new GdApiException(message, status, unsupported);
            }
            JsonElement parsed;
            try (InputStream input = connection.getInputStream()) {
                parsed = new JsonParser().parse(new String(readLimited(input), StandardCharsets.UTF_8));
            }
            markSuccess(source);
            return parsed;
        } catch (GdApiException error) {
            throw error;
        } catch (Exception error) {
            markFailure(source, describe(error), false);
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private static String errorDetail(HttpURLConnection connection) {
        try (InputStream stream = connection.getErrorStream()) {
            if (stream == null) return "";
            String body = new String(readLimited(stream), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(body);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                String detail = text(object, "detail");
                if (detail.isEmpty()) detail = firstError(object);
                return detail;
            }
            return body.length() > 160 ? body.substring(0, 160) : body;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isUnsupportedSourceDetail(String detail) {
        String value = safe(detail).toLowerCase(Locale.ROOT);
        return value.contains("source") && value.contains("not supported");
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = safe(throwable.getMessage());
        if (!message.isEmpty()) return message;
        return throwable.getClass().getSimpleName();
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
        String message = text(object, "detail");
        if (message.isEmpty()) message = text(object, "msg");
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

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsInt() : fallback;
        } catch (Throwable ignored) {
            return fallback;
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
