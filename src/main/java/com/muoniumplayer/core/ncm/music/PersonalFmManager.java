package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
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
    /** Prevents a failed first request from recursively reopening itself on every panel repaint. */
    private static final AtomicBoolean INITIAL_REQUEST_ISSUED = new AtomicBoolean(false);
    private static final Set<Long> RECENT_SONG_IDS = new LinkedHashSet<>();

    private static volatile List<Music> currentBatch = Collections.emptyList();
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
        return status;
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
