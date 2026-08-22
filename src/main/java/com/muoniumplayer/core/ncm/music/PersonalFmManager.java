package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns NetEase personal-FM retrieval. The FM page may display the current
 * batch before playback starts, but ordinary playback becomes an FM session
 * only when {@link CloudMusic#playFm(List, int)} is used.
 */
public final class PersonalFmManager {

    public enum Mode {
        DEFAULT("默认推荐", ""),
        FAMILIAR("熟悉模式", ""),
        EXPLORE("探索模式", ""),
        SCENE_RCMD("场景推荐", ""),
        AIDJ("AI DJ", "");

        private final String displayName;
        private final String defaultSubMode;

        Mode(String displayName, String defaultSubMode) {
            this.displayName = displayName;
            this.defaultSubMode = defaultSubMode;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDefaultSubMode() {
            return defaultSubMode;
        }
    }

    public static final String SCENE_EXERCISE = "EXERCISE";
    public static final String SCENE_FOCUS = "FOCUS";
    public static final String SCENE_NIGHT_EMO = "NIGHT_EMO";

    /** Personal FM is intentionally pull-based: fetch exactly one recommendation at a time. */
    private static final int BATCH_SIZE = 1;
    private static final int RECENT_ID_LIMIT = 24;
    private static final AtomicBoolean LOADING = new AtomicBoolean(false);
    /**
     * 后台"预备下一首"独占标志，刻意与 {@link #LOADING} 分开：预备是自动发生的，不能因为它在跑就
     * 让用户点刷新 / 换模式 / 跳过没反应，反过来也不能让用户操作被一次后台请求饿死。
     */
    private static final AtomicBoolean PREFETCHING = new AtomicBoolean(false);
    /** Prevents a failed first request from recursively reopening itself on every panel repaint. */
    private static final AtomicBoolean INITIAL_REQUEST_ISSUED = new AtomicBoolean(false);
    private static final Set<Long> RECENT_SONG_IDS = new LinkedHashSet<>();

    private static volatile List<Music> currentBatch = Collections.emptyList();
    /** 已经接进活队列、但还没播到的下一首；播到它时由 {@link #noteFmTrackStarted(Music)} 清掉。 */
    private static volatile Music prefetchedNext;
    private static volatile Mode selectedMode = Mode.DEFAULT;
    private static volatile String selectedSubMode = "";
    private static volatile String status = "进入后获取 1 首私人 FM 推荐";

    private PersonalFmManager() {
    }

    public static List<Music> getCurrentBatchSnapshot() {
        return new ArrayList<>(currentBatch);
    }

    public static Mode getSelectedMode() {
        return selectedMode;
    }

    public static String getSelectedSubMode() {
        return selectedSubMode;
    }

    public static String getStatus() {
        Music pending = prefetchedNext;
        return pending == null ? status : status + " · 已预备下一首";
    }

    /** 是否已经把下一首推荐预备进了活队列。 */
    public static boolean hasPrefetchedNext() {
        return prefetchedNext != null;
    }

    public static boolean isLoading() {
        return LOADING.get();
    }

    /** Loads once when the FM panel is first opened; errors remain visible until the user refreshes. */
    public static void ensureInitialLoad() {
        if (currentBatch.isEmpty() && !LOADING.get() && INITIAL_REQUEST_ISSUED.compareAndSet(false, true)) {
            requestBatch(selectedMode, selectedSubMode, true);
        }
    }

    /** Explicit user refresh: retry even if the previous request failed. */
    public static void openOrRefresh() {
        INITIAL_REQUEST_ISSUED.set(true);
        requestBatch(selectedMode, selectedSubMode, true);
    }


    public static void selectMode(Mode mode, String subMode) {
        selectedMode = mode == null ? Mode.DEFAULT : mode;
        selectedSubMode = selectedMode == Mode.SCENE_RCMD ? normalizeSceneSubMode(subMode) : "";
        requestBatch(selectedMode, selectedSubMode, CloudMusic.isPersonalFmActive());
    }

    /** Called by the FM-only player button. This is the only path that submits radio trash. */
    public static void skipCurrentAndRequestNext() {
        if (!CloudMusic.isPersonalFmActive() || !LOADING.compareAndSet(false, true)) {
            return;
        }

        final Music current = CloudMusic.currentlyPlaying;
        status = "正在切换私人 FM…";
        notifyUi();
        MultiThreadingUtil.runAsync(() -> {
            try {
                if (current != null && current.isNetease()) {
                    // Trash is intentionally explicit: merely entering FM or pressing normal next never calls it.
                    CloudMusicApi.personalFmTrash(current.getId());
                }
            } catch (Throwable throwable) {
                System.err.println("[NCM/FM] Skip feedback failed: " + safeMessage(throwable));
            } finally {
                LOADING.set(false);
            }
            requestBatch(selectedMode, selectedSubMode, true);
        });
    }

    /**
     * Triggered only when the small FM batch reaches its end or all of its
     * tracks fail. It does not mark any track as trash.
     */
    public static void requestNextBatchAsync() {
        requestBatch(selectedMode, selectedSubMode, true);
    }

    private static void requestBatch(Mode mode, String subMode, boolean startPlaybackAfterLoad) {
        if (!LOADING.compareAndSet(false, true)) {
            return;
        }
        if (CloudMusic.profile == null || CloudMusic.profile.getId() <= 0L) {
            status = "私人 FM 需要登录网易云账号";
            LOADING.set(false);
            notifyUi();
            return;
        }

        selectedMode = mode == null ? Mode.DEFAULT : mode;
        selectedSubMode = selectedMode == Mode.SCENE_RCMD ? normalizeSceneSubMode(subMode) : "";
        status = "正在获取私人 FM 推荐…";
        notifyUi();

        MultiThreadingUtil.runAsync(() -> {
            try {
                List<Music> loaded = fetchRecommendations();
                currentBatch = Collections.unmodifiableList(loaded);
                status = selectedMode.getDisplayName() + " · " + loaded.size() + " 首推荐";
                // playFm() establishes the isolated FM session itself, so first entry must not
                // require an already-active FM session before it can auto-start.
                if (startPlaybackAfterLoad) {
                    CloudMusic.playFm(loaded, 0);
                }
            } catch (Throwable throwable) {
                status = "私人 FM 获取失败：" + safeMessage(throwable);
                System.err.println("[NCM/FM] " + status);
            } finally {
                LOADING.set(false);
                notifyUi();
            }
        });
    }

    /**
     * 按当前选中的模式拉一次推荐并解析。请求失败、响应状态异常、没有可播放曲目都直接抛出，由调用方
     * 决定怎么反馈。抽出来给"用户触发的整批请求"和"后台预备下一首"共用，避免两条路径的接口参数和
     * 解析行为日后慢慢分叉。
     */
    private static List<Music> fetchRecommendations() throws Exception {
        String apiMode = selectedMode == Mode.AIDJ ? "aidj" : selectedMode.name();
        RequestUtil.RequestAnswer answer = selectedMode == Mode.DEFAULT && selectedSubMode.isEmpty()
                ? CloudMusicApi.personalFm()
                : CloudMusicApi.personalFmMode(apiMode, selectedSubMode, BATCH_SIZE);
        if (answer == null || answer.getStatus() != 200) {
            throw new IllegalStateException("接口响应状态 " + (answer == null ? "未知" : answer.getStatus()));
        }

        List<Music> loaded = parseSongs(answer.toJsonObject());
        if (loaded.isEmpty()) {
            throw new IllegalStateException("未返回可播放的私人 FM 歌曲");
        }
        return loaded;
    }

    /**
     * 私人 FM 的"预备下一首"：当前这首还在放的时候就把下一首拉回来，接到活队列尾部。
     *
     * <p>私人 FM 一次只拉一首（{@link #BATCH_SIZE}），旧流程是"当前这首播完 → 才去请求下一首 → 拿到
     * 之后 {@link CloudMusic#playFm(List, int)} 重开一条播放线程"。中间必然横着一次网络请求加一整轮
     * 下载解码的静音，而且因为队列里从来只有正在播的这一首，无缝切换（automix）预测下一首时永远拿到
     * -1，在私人 FM 下等于完全没生效。提前接上队列之后，automix 能像普通歌单那样提前预解码并交接，
     * 手动下一首也走同一条捷径。</p>
     *
     * <p>纯预备动作，边界刻意收得很紧：不提交 trash（那只属于用户点"跳过"）、不起播、不改面板上正在
     * 显示的推荐、失败也不覆盖 {@link #getStatus()} 里用户能看到的文案。用户触发的请求（刷新 / 换模式
     * / 跳过）正在跑时直接让路，避免两条路径抢同一份 {@code currentBatch}。</p>
     *
     * @param expectedQueue 发起预备时的活队列实例，回来时用它校验用户没有退出 FM 或换掉队列
     */
    public static void prefetchNextAsync(List<Music> expectedQueue) {
        if (expectedQueue == null || !CloudMusic.isPersonalFmActive()) return;
        if (prefetchedNext != null || LOADING.get()) return;
        if (CloudMusic.profile == null || CloudMusic.profile.getId() <= 0L) return;
        if (!PREFETCHING.compareAndSet(false, true)) return;

        MultiThreadingUtil.runAsync(() -> {
            try {
                final Music candidate = fetchRecommendations().get(0);
                // 队列改动统一回主线程做：界面线程正在遍历同一个 ArrayList。
                MultiThreadingUtil.runOnMainThread(() -> acceptPrefetchedTrack(expectedQueue, candidate));
            } catch (Throwable throwable) {
                // 预备失败不打扰用户：这首播完之后仍会走旧的 requestNextBatchAsync() 兜底路径。
                System.err.println("[NCM/FM] 预备下一首失败：" + safeMessage(throwable));
            } finally {
                PREFETCHING.set(false);
            }
        });
    }

    /** 主线程：把预备好的曲目接到活队列尾部。接不上就当没预备过，旧兜底路径照旧生效。 */
    private static void acceptPrefetchedTrack(List<Music> expectedQueue, Music candidate) {
        if (candidate == null || !CloudMusic.appendPrefetchedPersonalFmTrack(expectedQueue, candidate)) {
            return;
        }
        prefetchedNext = candidate;
        DownloadDynamicIsland.showPersonalFmPrefetched(candidate.getName());
        NCMScreen.getInstance().reloadCurrentPanel();
    }

    /**
     * 由播放线程在一首 FM 曲目真正开始出声时调用，普通起播与无缝交接两条路径都会走到。
     *
     * <p>做两件事：把已经消费掉的"预备下一首"标记清掉，让下一轮预备可以开始；把面板上显示的推荐换成
     * 此刻真正在播的这一首。有了预备之后 FM 的推进不再每首都经过 {@link #requestBatch}，所以这里必须
     * 补上原本由那次请求顺带完成的界面同步，否则私人 FM 页面会一直停在上一首。</p>
     */
    public static void noteFmTrackStarted(Music song) {
        if (song == null || !CloudMusic.isPersonalFmActive()) return;

        Music pending = prefetchedNext;
        if (pending != null && pending.getId() == song.getId()) {
            prefetchedNext = null;
        }

        List<Music> shown = currentBatch;
        if (shown.size() == 1 && shown.get(0) != null && shown.get(0).getId() == song.getId()) {
            return;
        }
        currentBatch = Collections.singletonList(song);
        status = selectedMode.getDisplayName() + " · 正在播放推荐";
        notifyUi();
    }

    /** 换歌单 / 退出 FM / 重开 FM 时调用：预备状态跟着旧的活队列一起作废。 */
    public static void clearPrefetchState() {
        prefetchedNext = null;
    }

    private static List<Music> parseSongs(JsonObject root) {
        JsonArray songs = extractSongArray(root);
        if (songs == null) {
            JsonObject singleSong = extractSingleSong(root);
            if (singleSong != null) {
                songs = new JsonArray();
                songs.add(singleSong);
            }
        }
        if (songs == null) return Collections.emptyList();

        List<Music> fresh = new ArrayList<>();
        List<Music> duplicates = new ArrayList<>();
        Set<Long> batchIds = new LinkedHashSet<>();
        for (JsonElement element : songs) {
            if (fresh.size() + duplicates.size() >= BATCH_SIZE) break;
            if (element == null || !element.isJsonObject()) continue;
            JsonObject songObject = element.getAsJsonObject();
            JsonObject nestedSong = object(songObject, "song");
            if (nestedSong != null) songObject = nestedSong;
            songObject = normalizeSongPayload(songObject);
            try {
                Music music = JsonUtils.parse(songObject, Music.class);
                if (music == null || music.getId() <= 0L || !batchIds.add(music.getId())) continue;
                music.applyNeteaseMetadata(songObject, null);
                if (wasRecentlyReturned(music.getId())) duplicates.add(music);
                else fresh.add(music);
            } catch (Throwable ignored) {
                // One malformed recommendation must never discard the whole small batch.
            }
        }

        List<Music> result = fresh.isEmpty() ? duplicates : fresh;
        remember(result);
        return result;
    }


    /**
     * Personal-FM payloads use the mobile field names artists/album/duration,
     * while the existing immutable DTO maps canonical song-detail names ar/al/dt.
     * Normalize at this narrow API boundary instead of changing every music source.
     */
    private static JsonObject normalizeSongPayload(JsonObject source) {
        JsonObject normalized = new JsonObject();
        if (source == null) return normalized;
        for (java.util.Map.Entry<String, JsonElement> entry : source.entrySet()) {
            normalized.add(entry.getKey(), entry.getValue());
        }
        copyIfMissing(normalized, "ar", source, "artists");
        copyIfMissing(normalized, "al", source, "album");
        copyIfMissing(normalized, "dt", source, "duration");
        copyIfMissing(normalized, "alia", source, "alias");
        return normalized;
    }

    private static void copyIfMissing(JsonObject target, String targetKey, JsonObject source, String sourceKey) {
        if (!target.has(targetKey) && source.has(sourceKey) && !source.get(sourceKey).isJsonNull()) {
            target.add(targetKey, source.get(sourceKey));
        }
    }


    private static JsonArray extractSongArray(JsonObject root) {
        if (root == null) return null;
        JsonArray direct = array(root, "data", "songs");
        if (direct != null) return direct;
        JsonObject data = object(root, "data");
        direct = array(data, "songs", "data");
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        return array(result, "songs", "data");
    }

    /** The basic /radio/get endpoint returns one song as data:{...}. */
    private static JsonObject extractSingleSong(JsonObject root) {
        if (root == null) return null;
        JsonObject data = object(root, "data");
        if (isSongObject(data)) return data;
        JsonObject result = object(root, "result");
        if (isSongObject(result)) return result;
        return isSongObject(root) ? root : null;
    }

    private static boolean isSongObject(JsonObject object) {
        return object != null && object.has("id") && object.has("name");
    }


    private static JsonArray array(JsonObject object, String... names) {
        if (object == null) return null;
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && value.isJsonArray()) return value.getAsJsonArray();
        }
        return null;
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null) return null;
        JsonElement value = object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static boolean wasRecentlyReturned(long songId) {
        synchronized (RECENT_SONG_IDS) {
            return RECENT_SONG_IDS.contains(songId);
        }
    }

    private static void remember(List<Music> songs) {
        synchronized (RECENT_SONG_IDS) {
            for (Music song : songs) {
                RECENT_SONG_IDS.add(song.getId());
            }
            while (RECENT_SONG_IDS.size() > RECENT_ID_LIMIT) {
                Long first = RECENT_SONG_IDS.iterator().next();
                RECENT_SONG_IDS.remove(first);
            }
        }
    }

    private static String normalizeSceneSubMode(String subMode) {
        if (SCENE_EXERCISE.equals(subMode) || SCENE_FOCUS.equals(subMode) || SCENE_NIGHT_EMO.equals(subMode)) {
            return subMode;
        }
        return SCENE_FOCUS;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "网络请求失败" : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "网络请求失败" : message.trim();
    }

    private static void notifyUi() {
        MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().reloadCurrentPanel());
    }
}
