package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.fpsmaster.music.AudioQuality;
import top.fpsmaster.music.Lyric;
import top.fpsmaster.music.MusicPlaylist;
import top.fpsmaster.music.MusicService;
import top.fpsmaster.music.QQUserInfo;
import top.fpsmaster.music.QrCode;
import top.fpsmaster.music.QrLoginState;
import top.fpsmaster.music.SongUrl;
import top.fpsmaster.music.Track;
import top.fpsmaster.music.http.MusicHttp;
import top.fpsmaster.music.store.MusicCredentialStore;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cadence 的宿主适配层。所有 QQ 音乐请求和新的跨平台网易云请求都从这里进入，
 * UI/播放器不直接持有 Cadence 的登录态与凭证文件。
 */
public final class CadenceMusicService {

    private static final MusicService SERVICE = new MusicService();
    private static final MusicCredentialStore CREDENTIALS = new MusicCredentialStore(
            ConfigPaths.MUSIC_AUTH);
    private static final MusicHttp QQ_HTTP = new MusicHttp();
    private static final Object QQ_PLAYLIST_LOCK = new Object();
    private static final long QQ_PLAYLIST_RETRY_DELAY_MS = 15_000L;

    private static volatile MusicPlatform currentPlatform = MusicPlatform.NETEASE;
    private static volatile QQUserInfo qqUserInfo;
    private static volatile List<PlayList> qqUserPlaylists = Collections.emptyList();
    private static volatile boolean qqUserPlaylistsLoaded;
    private static volatile boolean qqUserPlaylistsLoading;
    private static volatile long qqUserPlaylistsLastAttempt;
    private static volatile boolean initialized;

    private CadenceMusicService() {
    }

    public static synchronized void initialize(String legacyNeteaseCookie) {
        // Restore the persisted GD source choice on startup so selecting a platform in the
        // second-level source menu remains effective after restarting the client.
        if (GdStudioSourceSettings.isEnabled()) {
            currentPlatform = MusicPlatform.GD;
        }
        if (!initialized) {
            CREDENTIALS.load();
            SERVICE.getQq().setMusicid(nonNull(CREDENTIALS.getQqMusicId()));
            SERVICE.getQq().setMusicKey(nonNull(CREDENTIALS.getQqMusicKey()));
            initialized = true;
            if (SERVICE.getQq().getLoggedIn()) {
                refreshQQAccountDataAsyncSafe(null, false);
            }
        }

        String cookie = !isBlank(legacyNeteaseCookie)
                ? legacyNeteaseCookie
                : CREDENTIALS.getNeteaseCookie();
        SERVICE.getNetease().setCookie(nonNull(cookie));
        if (!isBlank(cookie)) {
            OptionsUtil.setCookie(cookie);
            CREDENTIALS.setNetease(cookie);
        }

        if (isLoggedIn(MusicPlatform.QQ) && qqUserInfo == null) {
            refreshQQAccountDataAsyncSafe(null, false);
        }
    }

    public static MusicPlatform getCurrentPlatform() {
        return currentPlatform;
    }

    public static synchronized void setCurrentPlatform(MusicPlatform platform) {
        MusicPlatform selected = platform == null ? MusicPlatform.NETEASE : platform;
        if (selected != MusicPlatform.GD) {
            // GD is an optional persisted provider. Selecting an official source must also
            // disable the persisted GD choice; otherwise initialize() restores GD before every
            // search and the UI appears to switch back to the official source while requests
            // still go to the third-party GD API.
            GdStudioSourceSettings.setPlatform("");
        }
        currentPlatform = selected;
    }

    public static List<Music> search(String keyword, int limit) {
        if (isBlank(keyword)) {
            return Collections.emptyList();
        }
        initialize(OptionsUtil.getCookie());
        if (currentPlatform == MusicPlatform.GD) {
            return searchGd(keyword, limit);
        }
        try {
            List<Track> tracks = SERVICE.search(currentPlatform.toCadenceSource(), keyword.trim(), Math.max(1, limit));
            return adaptTracks(tracks);
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] Search failed for " + currentPlatform + ": " + throwable.getMessage());
            return Collections.emptyList();
        }
    }
    private static List<Music> searchGd(String keyword, int limit) {
        String platform = GdStudioSourceSettings.getPlatform();
        if (platform.isEmpty()) return Collections.emptyList();
        // The GD API documents a 20-result default page; staying within it avoids excess payload.
        // searchWithFallback always tries the selected source first and only hands the query to a
        // healthy source when that source genuinely fails, so the screen never goes silently empty.
        GdStudioMusicService.SearchOutcome outcome = GdStudioMusicService.searchWithFallback(
                platform, keyword.trim(), Math.min(20, Math.max(1, limit)), 1);
        if (outcome.isFallback()) {
            DownloadDynamicIsland.showGdSearchFallback(GdStudioMusicService.displayName(platform),
                    GdStudioMusicService.displayName(outcome.source), outcome.requestedSourceFailed);
        } else if (outcome.isEmpty() && !outcome.failureReason.isEmpty()) {
            System.err.println("[Music/GD] Search failed for " + platform + ": " + outcome.failureReason);
            DownloadDynamicIsland.showGdSearchFailure(GdStudioMusicService.displayName(platform),
                    outcome.failureReason);
        }
        String servedSource = outcome.source.isEmpty() ? platform : outcome.source;
        List<Music> result = new ArrayList<>();
        for (GdStudioMusicService.GdTrack track : outcome.tracks) {
            Music adapted = Music.fromGdTrack(track, servedSource);
            if (adapted != null) result.add(adapted);
        }
        return result;
    }


    /**
     * Performs a caller-selected official-provider search without changing the visible provider.
     * This is used only for the platform explicitly chosen by the caller.
     */
    public static List<Music> search(MusicPlatform platform, String keyword, int limit) {
        if (isBlank(keyword) || platform == null) return Collections.emptyList();
        initialize(OptionsUtil.getCookie());
        try {
            return adaptTracks(SERVICE.search(platform.toCadenceSource(), keyword.trim(), Math.max(1, limit)));
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] Manual source search failed for " + platform + ": " + throwable.getMessage());
            return Collections.emptyList();
        }
    }
    public static List<Music> getQQTopTracks(int limit) {
        initialize(OptionsUtil.getCookie());
        try {
            return adaptTracks(SERVICE.getQq().getToplist(26, Math.max(1, limit)));
        } catch (Throwable first) {
            try {
                return adaptTracks(SERVICE.getQq().getToplist(4, Math.max(1, limit)));
            } catch (Throwable second) {
                System.err.println("[Music/Cadence] QQ toplist failed: " + second.getMessage());
                return Collections.emptyList();
            }
        }
    }

    /** QQ 发现页推荐歌单。失败时返回空列表，由主页决定是否回退到排行榜。 */
    public static List<PlayList> getQQRecommendPlaylists(int limit) {
        initialize(OptionsUtil.getCookie());
        try {
            List<MusicPlaylist> playlists = SERVICE.getQq().getRecommendPlaylists(Math.max(1, limit));
            return adaptQQPlaylists(playlists, false);
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] QQ recommendations failed: " + throwable.getMessage());
            return Collections.emptyList();
        }
    }

    /** 按 QQ 原始 dissid/tid 读取歌单曲目。 */
    public static List<Music> getQQPlaylistTracks(String playlistId, int limit) {
        if (isBlank(playlistId)) return Collections.emptyList();
        initialize(OptionsUtil.getCookie());
        try {
            return adaptTracks(SERVICE.getQq().getPlaylistTracks(playlistId.trim(), Math.max(1, limit)));
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] QQ playlist " + playlistId + " failed: " + throwable.getMessage());
            throw new IllegalStateException("QQ playlist request failed", throwable);
        }
    }

    public static List<PlayList> getQQUserPlaylistsSnapshot() {
        return new ArrayList<>(qqUserPlaylists);
    }

    public static boolean areQQUserPlaylistsLoaded() {
        return qqUserPlaylistsLoaded;
    }

    public static boolean areQQUserPlaylistsLoading() {
        return qqUserPlaylistsLoading;
    }

    /** 回调运行在后台线程；UI 调用方应自行切回主线程。 */
    public static void ensureQQUserPlaylistsAsync(Runnable callback) {
        refreshQQAccountDataAsyncSafe(callback, false);
    }

    /** 登录成功后强制刷新用户资料、创建歌单和收藏歌单。 */
    public static void refreshQQAccountData() {
        refreshQQUserInfo();
        refreshQQUserPlaylists(true);
    }

    /** Resolves with the current configured tier for legacy callers. */
    public static Tuple<String, String> getSongUrl(Music music) {
        return getSongUrl(music, CloudMusic.quality);
    }

    /** Resolves one Cadence stream attempt at an explicit tier without mutating global preferences. */
    public static Tuple<String, String> getSongUrl(Music music, Quality requestedQuality) {
        if (music == null) return null;
        if (music.isGd()) {
            try {
                Quality effectiveQuality = requestedQuality == null ? Quality.LOSSLESS : requestedQuality;
                GdStudioMusicService.ResolveResult result = GdStudioMusicService.resolveTrackWithFallback(
                        music.getGdPlatform(), music.getSourceId(), music.getName(),
                        music.getArtistsName(), effectiveQuality);
                return result == null ? null : new Tuple<>(result.url, result.format);
            } catch (Throwable throwable) {
                System.err.println("[Music/GD] Song URL failed for " + music.getStableKey() + ": " + throwable.getMessage());
                return null;
            }
        }
        initialize(OptionsUtil.getCookie());
        try {
            Track track = music.toCadenceTrack();
            Quality effectiveQuality = requestedQuality == null ? Quality.LOSSLESS : requestedQuality;
            SongUrl result = SERVICE.getSongUrl(track, mapQuality(effectiveQuality));
            if (result == null || !result.getAvailable() || isBlank(result.getUrl())) {
                return null;
            }
            String format = isBlank(result.getFormat()) ? inferFormat(result.getUrl()) : result.getFormat();
            return new Tuple<>(result.getUrl(), format);
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] Song URL failed for " + music.getStableKey() + ": " + throwable.getMessage());
            return null;
        }
    }

    public static Lyric getLyric(Music music) {
        if (music == null) return null;
        if (music.isGd()) {
            try {
                com.muoniumplayer.core.utils.Tuple<String, String> lrc =
                        GdStudioMusicService.getLyric(music.getGdPlatform(), music.getGdLyricId());
                if (lrc == null) return null;
                String original = lrc.getA() == null ? "" : lrc.getA();
                String translated = lrc.getB() == null ? "" : lrc.getB();
                List<top.fpsmaster.music.LyricLine> lines =
                        top.fpsmaster.music.lyric.LyricParser.INSTANCE.parse(original, translated, "");
                return new Lyric(original, translated, "", lines);
            } catch (Throwable throwable) {
                System.err.println("[Music/GD] Lyric failed for " + music.getStableKey() + ": " + throwable.getMessage());
                return null;
            }
        }
        initialize(OptionsUtil.getCookie());
        try {
            return SERVICE.getLyric(music.toCadenceTrack());
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] Lyric failed for " + music.getStableKey() + ": " + throwable.getMessage());
            return null;
        }
    }

    public static QrCode createQrCode(MusicPlatform platform) {
        initialize(OptionsUtil.getCookie());
        return SERVICE.createQrCode(platform.toCadenceSource());
    }

    public static QrLoginState checkQrCode(MusicPlatform platform, QrCode qrCode) {
        initialize(OptionsUtil.getCookie());
        return checkQrCodeSession(platform, qrCode);
    }

    /**
     * Checks the state of an already-created QR session without reinitializing the
     * Netease cookie.  Reinitializing during every polling pass overwrote the
     * temporary QR-login session with the previously active account's cookie,
     * which prevented a newly scanned Netease account from being persisted.
     *
     * <p>The QR session is initialized exactly once by {@link #createQrCode(MusicPlatform)}.
     * QQ callers retain the same Cadence state and are deliberately untouched.</p>
     */
    public static QrLoginState checkQrCodeSession(MusicPlatform platform, QrCode qrCode) {
        if (platform == null || qrCode == null) {
            return QrLoginState.ERROR;
        }
        initializeIfNeeded();
        QrLoginState state = SERVICE.checkQrCode(platform.toCadenceSource(), qrCode);
        if (state == QrLoginState.CONFIRMED) {
            persistLogin(platform);
        }
        return state;
    }

    public static synchronized void persistLogin(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ) {
            CREDENTIALS.setQq(SERVICE.getQq().getMusicid(), SERVICE.getQq().getMusicKey());
            refreshQQUserInfo();
        } else {
            String cookie = SERVICE.getNetease().getCookie();
            OptionsUtil.setCookie(nonNull(cookie));
            CREDENTIALS.setNetease(nonNull(cookie));
        }
    }

    public static synchronized void logout(MusicPlatform platform) {
        initialize(OptionsUtil.getCookie());
        if (platform == MusicPlatform.QQ) {
            SERVICE.getQq().clearLogin();
            CREDENTIALS.clearQq();
            qqUserInfo = null;
            clearQQPlaylistCache();
        } else {
            SERVICE.getNetease().clearLogin();
            CREDENTIALS.clearNetease();
            OptionsUtil.setCookie("");
            CloudMusic.profile = null;
            CloudMusic.playLists = null;
            CloudMusic.likeList = null;
            if (CloudMusic.COOKIE_FILE.exists() && !CloudMusic.COOKIE_FILE.delete()) {
                System.err.println("[Music/Cadence] Could not delete legacy NCM cookie file.");
            }
        }
    }

    public static boolean isLoggedIn(MusicPlatform platform) {
        initializeIfNeeded();
        if (platform == MusicPlatform.QQ) {
            return SERVICE.getQq().getLoggedIn();
        }
        return !isBlank(SERVICE.getNetease().getCookie()) || !isBlank(OptionsUtil.getCookie());
    }

    public static String getAccountName(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ) {
            QQUserInfo info = qqUserInfo;
            if (info != null && !isBlank(info.getNickname())) return info.getNickname();
            return isLoggedIn(platform) ? "QQ 音乐账号" : "未登录";
        }
        return CloudMusic.profile == null ? (isLoggedIn(platform) ? "网易云账号" : "未登录") : CloudMusic.profile.getName();
    }

    public static String getQQAvatarUrl() {
        QQUserInfo info = qqUserInfo;
        return info == null ? "" : nonNull(info.getAvatarUrl());
    }

    public static void refreshQQUserInfo() {
        if (!SERVICE.getQq().getLoggedIn()) {
            qqUserInfo = null;
            return;
        }
        try {
            qqUserInfo = SERVICE.getQq().getUserInfo();
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] QQ user info failed: " + throwable.getMessage());
        }
    }

    private static void refreshQQAccountDataAsyncSafe(Runnable callback, boolean force) {
        initializeIfNeeded();
        if (!SERVICE.getQq().getLoggedIn()) {
            qqUserInfo = null;
            clearQQPlaylistCache();
            if (callback != null) callback.run();
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (QQ_PLAYLIST_LOCK) {
            if (qqUserPlaylistsLoading) return;
            if (!force && qqUserPlaylistsLoaded) {
                if (callback != null) callback.run();
                return;
            }
            if (!force && now - qqUserPlaylistsLastAttempt < QQ_PLAYLIST_RETRY_DELAY_MS) return;
            qqUserPlaylistsLoading = true;
            qqUserPlaylistsLastAttempt = now;
        }

        Thread thread = new Thread(() -> {
            try {
                refreshQQUserInfo();
                refreshQQUserPlaylists(force);
            } finally {
                synchronized (QQ_PLAYLIST_LOCK) {
                    qqUserPlaylistsLoading = false;
                }
                if (callback != null) {
                    try {
                        callback.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "Cadence QQ Account");
        thread.setDaemon(true);
        thread.start();
    }

    private static void refreshQQUserPlaylists(boolean force) {
        if (!SERVICE.getQq().getLoggedIn()) {
            clearQQPlaylistCache();
            return;
        }
        if (!force && qqUserPlaylistsLoaded) return;

        try {
            List<PlayList> loaded = requestQQUserPlaylists();
            qqUserPlaylists = Collections.unmodifiableList(new ArrayList<>(loaded));
            qqUserPlaylistsLoaded = true;
            System.out.println("[Music/Cadence] Loaded " + loaded.size() + " QQ account playlists.");
        } catch (Throwable throwable) {
            qqUserPlaylistsLoaded = false;
            System.err.println("[Music/Cadence] QQ account playlists failed: " + throwable.getMessage());
        }
    }

    private static List<PlayList> requestQQUserPlaylists() {
        String musicId = nonNull(SERVICE.getQq().getMusicid()).trim();
        String musicKey = nonNull(SERVICE.getQq().getMusicKey()).trim();
        if (isBlank(musicId) || isBlank(musicKey)) return Collections.emptyList();

        LinkedHashMap<String, PlayList> merged = new LinkedHashMap<>();
        int successfulRequests = 0;
        Map<String, String> common = qqCommonQuery();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://y.qq.com/");
        headers.put("Origin", "https://y.qq.com");
        Map<String, String> cookies = qqCredentialCookies(musicId, musicKey);

        try {
            Map<String, String> query = new LinkedHashMap<>(common);
            query.put("hostuin", musicId);
            query.put("sin", "0");
            query.put("size", "100");
            JsonObject response = qqGetJson(
                    "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss", query, headers, cookies);
            appendQQAccountPlaylists(merged, response, false,
                    "disslist", "list", "playlist", "v_playlist");
            successfulRequests++;
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] QQ created playlists failed: " + throwable.getMessage());
        }

        try {
            Map<String, String> query = new LinkedHashMap<>(common);
            query.put("ct", "20");
            query.put("cv", "4747474");
            query.put("cid", "205360956");
            query.put("userid", musicId);
            query.put("reqtype", "3");
            query.put("sin", "0");
            query.put("ein", "99");
            JsonObject response = qqGetJson(
                    "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg", query, headers, cookies);
            appendQQAccountPlaylists(merged, response, true,
                    "cdlist", "disslist", "list", "playlist");
            successfulRequests++;
        } catch (Throwable throwable) {
            System.err.println("[Music/Cadence] QQ collected playlists failed: " + throwable.getMessage());
        }

        if (successfulRequests == 0) {
            throw new IllegalStateException("all QQ account playlist endpoints failed");
        }
        return new ArrayList<>(merged.values());
    }

    private static Map<String, String> qqCommonQuery() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("format", "json");
        query.put("inCharset", "utf8");
        query.put("outCharset", "utf-8");
        query.put("platform", "yqq.json");
        query.put("needNewCode", "0");
        return query;
    }

    private static Map<String, String> qqCredentialCookies(String musicId, String musicKey) {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("uin", musicId);
        cookies.put("qqmusic_uin", musicId);
        cookies.put("qm_keyst", musicKey);
        cookies.put("qqmusic_key", musicKey);
        return cookies;
    }

    private static JsonObject qqGetJson(String url, Map<String, String> query,
                                        Map<String, String> headers, Map<String, String> cookies) {
        MusicHttp.Response response = QQ_HTTP.get(url, query, headers, cookies);
        if (response == null || response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException("HTTP " + (response == null ? "null" : response.getStatus()));
        }

        String body = nonNull(response.getBody()).trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start > 0 && end > start) body = body.substring(start, end + 1);
        JsonElement parsed = new JsonParser().parse(body);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalStateException("QQ response is not a JSON object");
        }
        JsonObject object = parsed.getAsJsonObject();
        JsonElement code = object.get("code");
        if (code != null && !code.isJsonNull() && code.getAsInt() != 0) {
            throw new IllegalStateException("QQ API code=" + code.getAsInt());
        }
        return object;
    }

    private static void appendQQAccountPlaylists(Map<String, PlayList> destination, JsonObject root,
                                                  boolean subscribed, String... arrayNames) {
        JsonArray array = findArray(root, arrayNames);
        if (array == null) return;
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            try {
                JsonObject object = element.getAsJsonObject();
                String id = firstString(object, "dissid", "tid", "id", "dirid");
                String name = firstString(object, "diss_name", "dissname", "name", "title", "dirname");
                if (isBlank(id) || isBlank(name)) continue;
                String cover = firstString(object, "logo", "logoUrl", "picurl", "coverUrl", "cover");
                int count = firstInt(object, "song_cnt", "songnum", "song_count", "trackCount");
                String description = firstString(object, "desc", "description", "dissdesc");
                destination.putIfAbsent(id,
                        createQQPlaylist(id, name, cover, count, description, subscribed));
            } catch (Throwable ignored) {
                // A malformed item must not abort the remaining account playlists.
            }
        }
    }

    private static JsonArray findArray(JsonObject root, String... names) {
        return findArray(root, 0, names);
    }

    /**
     * QQ 的旧接口会把同一份结果包在 data、req_0、body 或若干未知包装对象中。
     * 不要只依赖某一层固定结构，否则接口字段轻微变化时“我的歌单”会变成空列表。
     * JSON 树没有循环引用，因此做一个有深度上限的递归搜索即可避免异常响应拖垮 UI。
     */
    private static JsonArray findArray(JsonObject root, int depth, String... names) {
        if (root == null || depth > 6) return null;

        for (String name : names) {
            JsonElement direct = root.get(name);
            if (direct != null && direct.isJsonArray()) return direct.getAsJsonArray();
        }

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonObject()) continue;
            JsonArray nested = findArray(value.getAsJsonObject(), depth + 1, names);
            if (nested != null) return nested;
        }
        return null;
    }
    private static String firstString(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (!isBlank(text)) return text.trim();
            } else if (value.isJsonObject()) {
                String nested = firstString(value.getAsJsonObject(), "url", "medium_url", "small_url");
                if (!isBlank(nested)) return nested;
            }
        }
        return "";
    }

    private static int firstInt(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value == null || value.isJsonNull()) continue;
            try {
                return Math.max(0, value.getAsInt());
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static List<PlayList> adaptQQPlaylists(List<MusicPlaylist> playlists, boolean subscribed) {
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        if (playlists == null) return new ArrayList<>();
        for (MusicPlaylist playlist : playlists) {
            if (playlist == null || isBlank(playlist.getId()) || isBlank(playlist.getName())) continue;
            unique.putIfAbsent(playlist.getId(), createQQPlaylist(playlist.getId(), playlist.getName(),
                    playlist.getCoverUrl(), playlist.getTrackCount(), playlist.getDescription(), subscribed));
        }
        return new ArrayList<>(unique.values());
    }

    private static PlayList createQQPlaylist(String sourceId, String name, String cover, int count,
                                              String description, boolean subscribed) {
        String cleanId = nonNull(sourceId).trim();
        PlayList playlist = new PlayList(stablePlaylistId(cleanId), nonNull(name), nonNull(cover),
                Math.max(0, count), 0L, null, nonNull(description), 0L);
        playlist.setSubscribed(subscribed);
        playlist.setPlatform(MusicPlatform.QQ);
        playlist.setPlatformPlaylistId(cleanId);
        return playlist;
    }

    private static long stablePlaylistId(String sourceId) {
        try {
            return Long.parseLong(sourceId);
        } catch (NumberFormatException ignored) {
            byte[] bytes = ("QQ:playlist:" + nonNull(sourceId)).getBytes(StandardCharsets.UTF_8);
            long hash = 0xcbf29ce484222325L;
            for (byte value : bytes) {
                hash ^= value & 0xffL;
                hash *= 0x100000001b3L;
            }
            return hash & Long.MAX_VALUE;
        }
    }

    private static void clearQQPlaylistCache() {
        qqUserPlaylists = Collections.emptyList();
        qqUserPlaylistsLoaded = false;
        qqUserPlaylistsLoading = false;
        qqUserPlaylistsLastAttempt = 0L;
    }
    private static void initializeIfNeeded() {
        if (!initialized) initialize(OptionsUtil.getCookie());
    }

    private static List<Music> adaptTracks(List<Track> tracks) {
        List<Music> result = new ArrayList<>();
        if (tracks == null) return result;
        for (Track track : tracks) {
            if (track != null) result.add(Music.fromCadenceTrack(track));
        }
        return result;
    }

    private static AudioQuality mapQuality(Quality quality) {
        if (quality == Quality.LOSSLESS || quality == Quality.HIRES
                || quality == Quality.JYEFFECT || quality == Quality.JYMASTER) {
            return AudioQuality.LOSSLESS;
        }
        if (quality == Quality.HIGHER || quality == Quality.EXHIGH || quality == Quality.SKY) {
            return AudioQuality.HIGH;
        }
        return AudioQuality.STANDARD;
    }

    private static String inferFormat(String url) {
        String clean = url == null ? "" : url.toLowerCase();
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 && dot < clean.length() - 1 ? clean.substring(dot + 1) : "mp3";
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
