package tritium.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.Cleanup;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import repackage.javazoom.jl.converter.Converter;
import repackage.org.kc7bfi.jflac.FLACDecoder;
import repackage.org.kc7bfi.jflac.PCMProcessor;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;
import repackage.org.kc7bfi.jflac.util.ByteData;
import repackage.org.kc7bfi.jflac.util.WavWriter;
import today.opai.api.enums.EnumChatColor;
import tritium.MuoniumPlayerExtension;
import tritium.interfaces.SharedConstants;
import tritium.ncm.OptionsUtil;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.ncm.music.dto.User;
import tritium.rendering.DownloadDynamicIsland;
import tritium.rendering.GaussianKernel;
import tritium.rendering.TextureManager;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.texture.Textures;
import tritium.screens.ncm.LyricLine;
import tritium.screens.ncm.LyricParser;
import tritium.screens.ncm.MusicLyricsPanel;
import tritium.screens.ncm.NCMScreen;
import tritium.widget.impl.MusicLyricsWidget;
import tritium.utils.Location;
import tritium.utils.Tuple;
import tritium.utils.json.JsonUtils;
import tritium.utils.network.HttpUtils;
import tritium.utils.other.StringUtils;
import tritium.utils.other.WrappedInputStream;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author IzumiiKonata
 * @since 6/16/2023 9:34 AM
 *
 * S3 适配：仅保留音频状态机 + 播放线程 + 下载/解码链（play/pause/seek/next/prev/stop/loop/shuffle）。
 * 歌词（LyricLine/LyricParser/MusicLyricsPanel）、封面（TextureManager/Textures/GaussianKernel）、
 * 登录二维码（QRCodeGenerator/NCMScreen）等 UI/渲染/歌词依赖分别归入 S4/S5/S6，
 * 相关方法在 S3 阶段以 no-op 占位并标注，届时从原项目原样回填。
 */
public class CloudMusic implements SharedConstants {

    @Getter
    private static final Map<String, String> headers = new HashMap<>();

    // 播放会话 generation：每次开始新歌递增，用于逻辑取消（logical cancellation）旧歌词任务。
    // 不要依赖 Thread.interrupt 判断歌词任务是否过期；结果提交前校验 generation + songId。
    public static final AtomicLong generation = new AtomicLong(0);

    /** 当前播放会话（volatile：播放线程写、主线程读，保证跨线程可见性）。 */
    public static volatile PlaybackSession currentSession = null;

    public static volatile AudioPlayer player;
    private static final Object PLAYER_STATE_LOCK = new Object();
    private static final long PLAY_THREAD_JOIN_TIMEOUT_MS = 1000L;
    // 当前播放列表
    public static List<Music> playList = new ArrayList<>();
    public static int curIdx = 0;
    public static volatile Music currentlyPlaying;
    /** The real NetEase playlist that started the current queue; null for searches/temporary lists. */
    public static volatile PlayList currentPlaylistContext;
    public static Thread playThread;

    public static volatile User profile;
    public static volatile List<PlayList> playLists;
    public static volatile List<Long> likeList;
    /** IDs from /api/v1/cloud/get, used to mark the same songs wherever they appear in lists. */
    private static volatile Set<Long> userCloudSongIds = Collections.emptySet();

    /** Prevents duplicate account refresh requests while the previous one is still running. */
    private static final AtomicBoolean NETEASE_REFRESHING = new AtomicBoolean(false);

    public static volatile PlayMode playMode = PlayMode.Sequential;
    /** Whether the current queue belongs to the isolated personal FM session. */
    private static volatile boolean personalFmActive;
    private static volatile PlayMode playModeBeforePersonalFm = PlayMode.Sequential;

    public static Quality quality = Quality.LOSSLESS;

    public static final List<LyricLine> lyrics = new CopyOnWriteArrayList<>();
    public static volatile LyricLine currentLyric = null;
    public static boolean hasTransLyrics = false;
    public static boolean hasRomanization = false;
    public static boolean haveNoWords = false;

    public static final File COOKIE_FILE = new File("NCMCookie.txt");

    @SneakyThrows
    public static void initNCM() {
        String cookie = getCookieFromFileOrOptions();
        CadenceMusicService.initialize(cookie);
        // Cadence 的凭证存储可能持有从新版账号管理中保存的网易云 Cookie。
        // 初始化后重新读取 Options，保证旧资料/歌单加载链路仍可复用。
        if (cookie.isEmpty()) {
            cookie = OptionsUtil.getCookie();
        }

        if (cookie.isEmpty()) {
            System.out.println("[NCM] Not logged in.");
        } else {
            loadNCM(cookie);
        }
    }

    @SneakyThrows
    private static String loadCookie() {
        if (!COOKIE_FILE.exists()) {
            return "";
        }

        List<String> cookieLines = Files.readAllLines(COOKIE_FILE.toPath());
        return cookieLines.isEmpty() ? "" : cookieLines.get(0);
    }

    private static String getCookieFromFileOrOptions() {
        String optionCookie = OptionsUtil.getCookie();
        return optionCookie == null || optionCookie.isEmpty() ? loadCookie() : optionCookie;
    }

    public static void loadNCM(String cookie) {
        OptionsUtil.setCookie(cookie);
        // 获取用户信息
        profile = getUserProfile();

        if (profile == null) {
            return;
        }

        System.out.printf("[NCM] Logged in as %s(%s)\n", profile.getName(), profile.getId());

        if (!OptionsUtil.getCookie().isEmpty()) {
            onStop();
        }

        CloudMusic.playLists = loadUserPlaylists();
        System.out.printf("[NCM] Loaded %s playlists\n", playLists.size());

        likeList = likeList();
        Set<Long> loadedCloudSongIds = loadUserCloudSongIds();
        if (loadedCloudSongIds != null) {
            userCloudSongIds = loadedCloudSongIds;
            System.out.printf("[NCM] Loaded %s cloud-drive song markers%n", userCloudSongIds.size());
        }
        NCMScreen.getInstance().markDirty();
        MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().reloadCurrentPanel());
    }

    private static List<PlayList> loadUserPlaylists() {
        List<PlayList> userPlaylists = new ArrayList<>();
        int page = 0;

        while (true) {
            List<PlayList> pagePlaylists = fetchPlaylistsPage(page);

            if (pagePlaylists.isEmpty()) {
                break;
            }

            userPlaylists.addAll(pagePlaylists);
            page++;
        }

        return userPlaylists;
    }

    private static List<PlayList> fetchPlaylistsPage(int page) {
        try {
            return profile.playLists(page, 30);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Attempts to claim the single网易云刷新 slot. */
    public static boolean beginNeteaseRefresh() {
        return NETEASE_REFRESHING.compareAndSet(false, true);
    }

    public static void endNeteaseRefresh() {
        NETEASE_REFRESHING.set(false);
    }

    /**
     * Reloads the logged-in网易云 account data without invoking initial-login side effects.
     * Network work is performed on the caller's thread. Published snapshots are replaced only
     * after all requests have completed successfully, so transient failures preserve old data.
     */
    public static NeteaseRefreshResult refreshNeteaseAccountData() {
        long startedAt = System.currentTimeMillis();
        String cookie = getCookieFromFileOrOptions();
        if (cookie == null || cookie.trim().isEmpty()) {
            return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, "未登录网易云账号");
        }

        try {
            OptionsUtil.setCookie(cookie);
            User refreshedProfile = getUserProfile();
            if (refreshedProfile == null) {
                return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, "登录状态已失效");
            }

            List<PlayList> refreshedPlaylists = loadUserPlaylistsStrict(refreshedProfile);
            List<Long> refreshedLikeList = loadLikeList(refreshedProfile);
            Set<Long> refreshedCloudSongIds = loadUserCloudSongIds();

            profile = refreshedProfile;
            playLists = refreshedPlaylists;
            likeList = refreshedLikeList;
            if (refreshedCloudSongIds != null) {
                userCloudSongIds = refreshedCloudSongIds;
            }
            return NeteaseRefreshResult.success(System.currentTimeMillis() - startedAt,
                    refreshedPlaylists.size(), refreshedLikeList.size());
        } catch (Throwable throwable) {
            String detail = throwable.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = "网络请求失败";
            }
            System.err.println("[NCM] Playlist refresh failed: " + detail);
            return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, detail);
        }
    }

    private static List<PlayList> loadUserPlaylistsStrict(User user) {
        List<PlayList> userPlaylists = new ArrayList<>();
        int page = 0;
        // A sane upper bound prevents a malformed API response from creating an endless loop.
        while (page < 1000) {
            List<PlayList> pagePlaylists = user.playLists(page, 30);
            if (pagePlaylists == null || pagePlaylists.isEmpty()) {
                break;
            }
            userPlaylists.addAll(pagePlaylists);
            page++;
        }
        if (page >= 1000) {
            throw new IllegalStateException("歌单数量异常");
        }
        return userPlaylists;
    }

    /** Returns whether a NetEase song is present in the authenticated user's cloud drive. */
    public static boolean isUserCloudSong(long songId) {
        return songId > 0L && userCloudSongIds.contains(songId);
    }

    /**
     * Loads cloud-drive IDs separately from playlist details. Normal playlist responses do not
     * reliably contain a cloud marker, even when the current account uploaded the same song.
     */
    private static Set<Long> loadUserCloudSongIds() {
        final int pageSize = 200;
        final int maximumPages = 100;
        Set<Long> result = new HashSet<>();
        try {
            for (int page = 0; page < maximumPages; page++) {
                JsonObject response = CloudMusicApi.userCloudSongs(pageSize, page * pageSize).toJsonObject();
                JsonArray entries = extractCloudEntries(response);
                if (entries == null || entries.size() == 0) {
                    break;
                }
                for (JsonElement element : entries) {
                    if (element != null && element.isJsonObject()) {
                        long songId = extractCloudSongId(element.getAsJsonObject());
                        if (songId > 0L) {
                            result.add(songId);
                        }
                    }
                }
                boolean hasMore = response.has("hasMore") && !response.get("hasMore").isJsonNull()
                        && response.get("hasMore").getAsBoolean();
                if (!hasMore && entries.size() < pageSize) {
                    break;
                }
            }
            return Collections.unmodifiableSet(result);
        } catch (Throwable throwable) {
            System.err.println("[NCM] Cloud-drive marker load failed: " + throwable.getMessage());
            return null;
        }
    }

    /**
     * The official endpoint normally returns data as an array. Keep the parser
     * tolerant of proxy/API wrappers that put the array under data.songs or
     * data.data; otherwise a successful response would silently produce no
     * badges at all.
     */
    private static JsonArray extractCloudEntries(JsonObject response) {
        if (response == null) return null;
        JsonElement data = response.get("data");
        if (data != null && data.isJsonArray()) return data.getAsJsonArray();
        if (data != null && data.isJsonObject()) {
            JsonObject dataObject = data.getAsJsonObject();
            JsonElement songs = dataObject.get("songs");
            if (songs != null && songs.isJsonArray()) return songs.getAsJsonArray();
            JsonElement nestedData = dataObject.get("data");
            if (nestedData != null && nestedData.isJsonArray()) return nestedData.getAsJsonArray();
        }
        JsonElement songs = response.get("songs");
        return songs != null && songs.isJsonArray() ? songs.getAsJsonArray() : null;
    }
    private static long extractCloudSongId(JsonObject cloudEntry) {
        JsonObject simpleSong = cloudEntry.has("simpleSong") && cloudEntry.get("simpleSong").isJsonObject()
                ? cloudEntry.getAsJsonObject("simpleSong") : null;
        long id = readCloudSongId(simpleSong, "id");
        if (id > 0L) return id;
        JsonObject song = cloudEntry.has("song") && cloudEntry.get("song").isJsonObject()
                ? cloudEntry.getAsJsonObject("song") : null;
        id = readCloudSongId(song, "id");
        if (id > 0L) return id;
        id = readCloudSongId(cloudEntry, "songId");
        return id > 0L ? id : readCloudSongId(cloudEntry, "id");
    }

    private static long readCloudSongId(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    @lombok.Getter
    public static final class NeteaseRefreshResult {
        private final boolean success;
        private final int playlistCount;
        private final int likeCount;
        private final long elapsedMillis;
        private final String message;

        private NeteaseRefreshResult(boolean success, long elapsedMillis, int playlistCount,
                                     int likeCount, String message) {
            this.success = success;
            this.elapsedMillis = elapsedMillis;
            this.playlistCount = playlistCount;
            this.likeCount = likeCount;
            this.message = message;
        }

        private static NeteaseRefreshResult success(long elapsedMillis, int playlistCount, int likeCount) {
            return new NeteaseRefreshResult(true, elapsedMillis, playlistCount, likeCount, "刷新成功");
        }

        private static NeteaseRefreshResult failure(long elapsedMillis, String message) {
            return new NeteaseRefreshResult(false, elapsedMillis, 0, 0,
                    message == null || message.trim().isEmpty() ? "刷新失败" : message.trim());
        }
    }

    @SneakyThrows
    public static void onStop() {
        Files.write(COOKIE_FILE.toPath(), OptionsUtil.getCookie().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Getter
    public enum PlayMode {
        Random("F", "随机播放"),
        LoopInList("I", "列表循环"),
        LoopSingle("L", "单曲循环"),
        Sequential("G", "顺序播放");

        private final String icon;
        private final String displayName;

        PlayMode(String icon, String displayName) {
            this.icon = icon;
            this.displayName = displayName;
        }
    }

    /**
     * 供播放器控制栏使用的播放模式循环：顺序播放 → 随机播放 → 单曲循环。
     * 列表循环保留给旧数据/兼容逻辑，不再作为主播放器的可见模式。
     */
    public static synchronized PlayMode cyclePlayMode() {
        // Personal FM owns its queue order; ordinary modes do not mutate it.
        if (personalFmActive) return playMode;
        switch (playMode) {
            case Sequential:
            case LoopInList:
                playMode = PlayMode.Random;
                prepareRandomQueue();
                break;
            case Random:
                playMode = PlayMode.LoopSingle;
                break;
            case LoopSingle:
            default:
                playMode = PlayMode.Sequential;
                break;
        }
        return playMode;
    }

    /**
     * 切入随机模式时保留正在播放的歌曲，并把它放在随机队列的开头。
     * 这样当前歌曲不会被中断，下一首立即按随机顺序播放。
     */
    private static void prepareRandomQueue() {
        if (playList == null || playList.size() < 2) {
            return;
        }

        Music currentSong = currentlyPlaying;
        int currentIndex = currentSong == null ? curIdx : playList.indexOf(currentSong);
        if (currentIndex < 0 || currentIndex >= playList.size()) {
            currentIndex = 0;
        }

        Music anchorSong = playList.get(currentIndex);
        List<Music> shuffledSongs = new ArrayList<>(playList);
        shuffledSongs.remove(anchorSong);
        Collections.shuffle(shuffledSongs);
        shuffledSongs.add(0, anchorSong);

        // 保持 List 实例不变，播放线程据此识别“是否切换了播放列表”。
        // 使用逐项覆盖而非 clear/addAll，避免播放线程在极短窗口读取到空队列。
        Collections.copy(playList, shuffledSongs);
        curIdx = 0;
    }

    private static void reshuffleRandomQueueForNextCycle(List<Music> songs) {
        if (songs.size() < 2) {
            return;
        }

        Music justFinished = currentlyPlaying;
        Collections.shuffle(songs);

        // 避免一个随机轮次结束后立刻又播放刚刚结束的那首。
        if (justFinished != null && songs.get(0).equals(justFinished)) {
            for (int i = 1; i < songs.size(); i++) {
                if (!songs.get(i).equals(justFinished)) {
                    Collections.swap(songs, 0, i);
                    break;
                }
            }
        }
    }

    // 以下这些 上一首/下一首 以及 播放/暂停 的逻辑我写完我自己都看不懂
    // BUT IT WORKS
    public static volatile boolean dontAdd = false;

    public static void prev() {
        if (personalFmActive && curIdx <= 0) return;
        updatePlayCountIfNeeded();

        if (!canPlayPrevious()) {
            return;
        }

        if (player != null && !playList.isEmpty()) {
            prepareForTrackChange();
            curIdx--;
            stopCurrentPlayback();
        }
    }

    private static boolean canPlayPrevious() {
        if (curIdx - 1 >= 0) {
            return true;
        }

        if (playMode == PlayMode.LoopInList || playMode == PlayMode.Random || playMode == PlayMode.LoopSingle) {
            curIdx = playList.size();
            return true;
        }

        return false;
    }

    public static void next() {
        if (personalFmActive && curIdx + 1 >= playList.size()) {
            PersonalFmManager.requestNextBatchAsync();
            return;
        }
        if (!canPlayNext()) {
            return;
        }

        if (player != null && !playList.isEmpty()) {
            updatePlayCountIfNeeded();
            prepareForTrackChange();
            curIdx++;
            stopCurrentPlayback();
        }
    }

    /**
     * Returns the persisted player volume normalized to {@code 0.0..1.0}.
     * The HUD module owns the stored Value so new AudioPlayer instances pick up
     * exactly the same level after a track switch.
     */
    public static float getVolume() {
        try {
            return clampVolume(MuoniumPlayerExtension.getInstance().musicInfo.volume.getValue().floatValue());
        } catch (RuntimeException ignored) {
            AudioPlayer activePlayer = player;
            return activePlayer == null ? 0.10f : clampVolume(activePlayer.getVolume());
        }
    }

    /**
     * Applies volume through one path for the two player UIs and global hotkeys.
     * Values are quantized to the existing one-percent configuration precision.
     *
     * @return whether the persisted value changed
     */
    public static boolean setVolume(float volume, boolean showNotice) {
        float safeVolume = Math.round(clampVolume(volume) * 100.0f) / 100.0f;
        float previousVolume = getVolume();
        if (Math.abs(previousVolume - safeVolume) < .0001f) {
            return false;
        }

        try {
            MuoniumPlayerExtension.getInstance().musicInfo.volume.setValue((double) safeVolume);
        } catch (RuntimeException ignored) {
            // The active player still receives the new value below if the module is not ready.
        }

        AudioPlayer activePlayer = player;
        if (activePlayer != null) {
            activePlayer.setVolume(safeVolume);
        }

        if (showNotice) {
            DownloadDynamicIsland.showVolume(Math.round(safeVolume * 100.0f));
        }
        return true;
    }

    /** Adjusts volume by a normalized delta and reports the resulting percentage. */
    public static boolean adjustVolume(float delta) {
        return setVolume(getVolume() + delta, true);
    }

    private static float clampVolume(float volume) {
        if (Float.isNaN(volume) || Float.isInfinite(volume)) {
            return .10f;
        }
        return Math.max(0.0f, Math.min(1.0f, volume));
    }
    /**
     * Moves the current track by a relative amount and immediately aligns both
     * lyric renderers with the audio player's real clock. Keeping this in the
     * playback layer prevents each UI surface from implementing a subtly
     * different seek path.
     */
    public static boolean seekByMillis(float deltaMillis) {
        AudioPlayer activePlayer = player;
        if (activePlayer == null || currentlyPlaying == null) {
            return false;
        }

        float total = activePlayer.getTotalTimeMillis();
        if (total <= 0.0f) {
            return false;
        }

        float target = Math.max(0.0f, Math.min(total,
                activePlayer.getCurrentTimeMillis() + deltaMillis));
        activePlayer.setPlaybackTime(target);

        float actual = activePlayer.getCurrentTimeMillis();
        MusicLyricsWidget.resetProgress(actual);
        MusicLyricsPanel.resetProgress(actual);
        return true;
    }

    private static boolean canPlayNext() {
        if (curIdx + 1 <= playList.size() - 1) {
            return true;
        }

        if (playMode == PlayMode.Sequential) {
            return false;
        }

        // 非顺序模式下，手动下一首可从队列首位继续。
        curIdx = -1;
        return true;
    }

    /**
     * 给网易云发送当前歌曲的播放时长
     */
    private static void updatePlayCountIfNeeded() {
        if (playedFrom != null && player != null) {
            playList.get(curIdx).updPlayCount(playedFrom, player.getCurrentTimeSeconds());
        }
    }

    private static void prepareForTrackChange() {
        dontAdd = true;
    }

    private static void stopCurrentPlayback() {
        player.close();
        playing.set(false);
    }

    /**
     * 播放来源, 用于记录播放时长
     */
    public static PlayList playedFrom = null;

    /**
     * 播放给定的列表中的所有歌曲
     * @param songs 歌曲列表
     * @param startIdx 第一首播放的索引
     */
    @SneakyThrows
    public static void play(List<Music> songs, int startIdx) {
        exitPersonalFmSession();
        startPlaybackList(songs, startIdx);
    }

    /** Starts a small personal FM batch without applying ordinary playlist modes. */
    @SneakyThrows
    public static void playFm(List<Music> songs, int startIdx) {
        if (songs == null || songs.isEmpty()) {
            System.err.println("[NCM] Ignoring empty personal FM batch.");
            return;
        }
        enterPersonalFmSession();
        startPlaybackList(songs, startIdx);
    }

    @SneakyThrows
    private static void startPlaybackList(List<Music> songs, int startIdx) {
        if (songs == null || songs.isEmpty()) {
            System.err.println("[NCM] Ignoring empty playlist.");
            return;
        }

        // 深拷贝一份以避免打乱时影响来源列表；FM 会话始终保持接口返回顺序。
        List<Music> safeSongList = new ArrayList<>(songs);
        stopExistingPlayThread();

        if (!personalFmActive && playMode == PlayMode.Random) {
            startIdx = handleRandomPlayMode(safeSongList, startIdx);
        }

        startIdx = normalizeStartIndex(startIdx);
        loadMusicCover(safeSongList.get(Math.min(startIdx, safeSongList.size() - 1)));
        playList = safeSongList;
        startNewPlayThread(safeSongList, startIdx);
    }

    public static boolean isPersonalFmActive() {
        return personalFmActive;
    }

    public static synchronized void enterPersonalFmSession() {
        if (!personalFmActive) {
            playModeBeforePersonalFm = playMode;
            personalFmActive = true;
            playMode = PlayMode.Sequential;
        }
    }

    public static synchronized void exitPersonalFmSession() {
        if (!personalFmActive) return;
        personalFmActive = false;
        playMode = playModeBeforePersonalFm;
    }

    /**
     * 使当前播放会话失效（逻辑取消），用于切歌/切列表前阻止旧歌词任务污染新状态。
     * 不在此 increment generation：新歌 beginNewSession() 时再递增。
     */
    private static void invalidateCurrentSession() {
        PlaybackSession old = currentSession;
        if (old != null) {
            old.invalidate();
        }
    }

    /**
     * 开始播放一首新歌时生成唯一 Session：先失效旧 Session，再递增 generation，最后绑定新 Session。
     */
    public static PlaybackSession beginNewSession(Music song) {
        PlaybackSession old = currentSession;
        if (old != null) {
            old.invalidate();
        }
        long id = generation.incrementAndGet();
        PlaybackSession session = new PlaybackSession(id, song.getStableKey(), song.getId());
        currentSession = session;
        return session;
    }

    private static void stopExistingPlayThread() throws InterruptedException {
        invalidateCurrentSession();
        Thread previousThread = playThread;
        if (previousThread == null) return;

        doBreak = true;
        playing.set(false);
        if (previousThread instanceof PlayThread) {
            ((PlayThread) previousThread).cancelPlayback();
        } else {
            previousThread.interrupt();
        }

        // 网络实现可能忽略 interrupt。禁止无限 join 卡住 GUI/重新点歌入口。
        previousThread.join(PLAY_THREAD_JOIN_TIMEOUT_MS);
        if (previousThread.isAlive()) {
            System.err.println("[NCM] Previous play thread did not stop in time; continuing with a new session.");
        }
        if (playThread == previousThread) {
            playThread = null;
        }
    }

    private static int handleRandomPlayMode(List<Music> songs, int startIdx) {
        if (startIdx == -1) {
            Collections.shuffle(songs);
        } else {
            Music selectedMusic = songs.get(startIdx);
            Collections.shuffle(songs);
            startIdx = songs.indexOf(selectedMusic);
        }
        return startIdx;
    }

    private static int normalizeStartIndex(int startIdx) {
        return startIdx == -1 ? 0 : startIdx;
    }

    private static void startNewPlayThread(List<Music> songs, int startIdx) {
        playThread = new PlayThread(songs, startIdx);
        doBreak = false;
        playing.set(false);
        playThread.start();
    }

    static volatile boolean doBreak = false;

    static AtomicBoolean playing = new AtomicBoolean(true);

    private static class PlayThread extends Thread {
        private final List<Music> songs;
        private final int startIdx;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private PlaybackSession session;
        private volatile AudioPlayer ownedPlayer;

        public PlayThread(List<Music> songs, int startIdx) {
            this.songs = songs;
            this.setName("Play Thread");
            this.startIdx = startIdx;
        }

        @Override
        public void run() {
            curIdx = startIdx;
            int consecutiveLoadFailures = 0;

            while (shouldContinuePlayback()) {
                if (playListChanged()) {
                    break;
                }

                Music currentSong = playList.get(curIdx);
                prepareForPlayback();

                if (!playSong(currentSong)) {
                    // 乱序播放更容易首先命中无版权、下架或临时无法获取 URL 的曲目。
                    // 旧实现会因为一首歌失败就直接结束整个播放线程，表现为“随机播放偶尔无法加载”。
                    consecutiveLoadFailures++;
                    if (shouldStopAfterLoadFailure(consecutiveLoadFailures)) {
                        break;
                    }
                    updateCurrentIndex();
                    continue;
                }

                consecutiveLoadFailures = 0;
                preloadNextCover();
                waitForPlaybackCompletion();
                if (isPlaybackCancelled() || playListChanged() || session == null || !session.isActive()) {
                    break;
                }
                handlePlaybackCompletion();
                updateCurrentIndex();
                if (personalFmActive && curIdx >= playList.size()) {
                    PersonalFmManager.requestNextBatchAsync();
                    break;
                }
            }
        }

        private boolean shouldStopAfterLoadFailure(int consecutiveLoadFailures) {
            if (doBreak || isPlaybackCancelled() || playListChanged()) {
                return true;
            }

            if (personalFmActive && consecutiveLoadFailures >= playList.size()) {
                PersonalFmManager.requestNextBatchAsync();
                return true;
            }
            if (consecutiveLoadFailures < playList.size()) {
                return false;
            }

            System.err.println("[NCM] No playable songs were found in the current playlist.");
            api.printMessage(EnumChatColor.RED + "当前歌单没有可播放的歌曲");
            DownloadDynamicIsland.showPlaybackFailure("当前歌单", "没有可播放的歌曲");
            return true;
        }

        private boolean shouldContinuePlayback() {
            return curIdx >= 0 && curIdx < playList.size() && !doBreak && !isPlaybackCancelled();
        }

        private boolean isPlaybackCancelled() {
            return cancelled.get() || this.isInterrupted();
        }

        private boolean isSessionUsable(PlaybackSession targetSession) {
            return !isPlaybackCancelled() && targetSession != null && targetSession.isActive()
                    && !playListChanged();
        }

        private void cancelPlayback() {
            cancelled.set(true);
            PlaybackSession activeSession = session;
            if (activeSession != null) activeSession.invalidate();
            interrupt();

            synchronized (PLAYER_STATE_LOCK) {
                AudioPlayer activePlayer = ownedPlayer;
                if (activePlayer != null && CloudMusic.player == activePlayer) {
                    try {
                        activePlayer.close();
                    } catch (Throwable ignored) {
                        // 播放器可能尚未完全初始化；取消流程不能再阻塞新的点歌请求。
                    }
                }
                playing.set(false);
            }
        }

        private boolean playListChanged() {
            return playList != songs;
        }

        private void prepareForPlayback() {
            stopPreviousPlayer();
            loadMusicCover(playList.get(curIdx));
        }

        private boolean playSong(Music song) {
            if (isPlaybackCancelled()) return false;

            // 先使旧 session 失效并生成新 session/generation，绑定本次歌曲。
            session = CloudMusic.beginNewSession(song);
            if (!isSessionUsable(session)) {
                session.invalidate();
                return false;
            }
            currentlyPlaying = song;

            // 每次点击/切歌都会重新创建解析任务；失败结果不会被缓存。
            Tuple<String, String> playUrl = song.getPlayUrl();
            if (!isSessionUsable(session)) return false;

            if (playUrl == null) {
                handleUnplayableSong(song);
                return false;
            }

            boolean started = initializeAndPlaySong(song, playUrl, session);
            if (started && isSessionUsable(session)) {
                DownloadDynamicIsland.showPlaybackQuality(
                        song.getPlaybackQuality().getDisplayName(), playUrl.getB());
            }
            return started;
        }

        private boolean initializeAndPlaySong(Music song, Tuple<String, String> playUrl, PlaybackSession targetSession) {
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.cancelDownload();

            File musicFile;
            try {
                musicFile = getMusicFile(playUrl, song);
            } catch (Exception e) {
                if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                    if (e instanceof UnsupportedMp4ContainerException) {
                        System.err.println("[NCM] " + e.getMessage());
                    } else {
                        handlePlayerInitializationError(song, e);
                    }
                }
                return false;
            }

            // URL 获取、下载或解码期间可能已经切歌。旧线程绝不能覆盖新会话的播放器。
            if (!isSessionUsable(targetSession)) return false;

            synchronized (PLAYER_STATE_LOCK) {
                if (!isSessionUsable(targetSession)) return false;
                try {
                    player = initializePlayer(musicFile);
                    ownedPlayer = player;
                    targetSession.player = ownedPlayer;
                } catch (Exception e) {
                    if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                        handlePlayerInitializationError(song, e);
                    }
                    return false;
                }
            }

            if (!isSessionUsable(targetSession)) return false;

            // 歌词异步加载绑定本 Session（两阶段提交），歌词可先到或后到。
            // 网盘歌曲额外携带本地缓存文件，用于优先读取音频标签中的内嵌 LRC/YRC。
            loadLyric(song, targetSession, musicFile);
            if (!startPlayback(song, playUrl, musicFile, targetSession)) return false;
            targetSession.audioActive = true;
            return true;
        }

        private void waitForPlaybackCompletion() {
            while (playing.get()) {
                if (doBreak || !isSessionUsable(session)) break;

                AudioPlayer activePlayer = ownedPlayer;
                if (activePlayer == null) break;
                CloudMusic.updateCurrentLyric(activePlayer.getCurrentTimeMillis());

                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private void handlePlaybackCompletion() {
            AudioPlayer completedPlayer = ownedPlayer;
            if (completedPlayer == null || isPlaybackCancelled()) return;

            if (!dontAdd && playedFrom != null && curIdx >= 0 && curIdx < playList.size()) {
                playList.get(curIdx).updPlayCount(playedFrom, completedPlayer.getCurrentTimeSeconds());
            }

            synchronized (PLAYER_STATE_LOCK) {
                if (!isPlaybackCancelled() && ownedPlayer == completedPlayer) {
                    completedPlayer.close();
                }
            }
        }

        private void stopPreviousPlayer() {
            synchronized (PLAYER_STATE_LOCK) {
                AudioPlayer previousPlayer = CloudMusic.player;
                if (previousPlayer != null && !previousPlayer.isFinished()) {
                    previousPlayer.close();
                }
            }
            if (!isPlaybackCancelled()) sleep(250);
        }

        private void handleUnplayableSong(Music song) {
            api.printMessage(EnumChatColor.RED + "无法播放: " + song.getName() + " - " + song.getArtistsName());
            DownloadDynamicIsland.showPlaybackFailure(song.getName(), "没有可用音频源");

            System.err.printf("%s无法播放: %s - %s, 可能因为该歌曲没有版权\n", EnumChatColor.RED, song.getName(), song.getArtistsName());
        }

        private void handlePlayerInitializationError(Music song, Exception e) {
            e.printStackTrace();
            String message = e.getMessage();
            DownloadDynamicIsland.showPlaybackFailure(song == null ? "当前歌曲" : song.getName(),
                    message == null || message.trim().isEmpty() ? "音频加载或解码失败" : "音频加载失败");
            System.err.printf(EnumChatColor.RED + "[NCM] Failed to initiate audio player! Error: %s\n", message);
        }

        private boolean startPlayback(Music song, Tuple<String, String> playUrl, File musicFile,
                                      PlaybackSession targetSession) {
            AudioPlayer activePlayer = ownedPlayer;
            if (activePlayer == null || !isSessionUsable(targetSession)) return false;

            try {
                synchronized (PLAYER_STATE_LOCK) {
                    if (!isSessionUsable(targetSession) || ownedPlayer != activePlayer) return false;
                    activePlayer.play();
                }
            } catch (ChannelMismatchException mismatch) {
                try {
                    activePlayer.player.cleanUp();
                    musicFile.delete();
                    if (!isSessionUsable(targetSession)) return false;

                    File retryFile = getMusicFile(playUrl, song);
                    synchronized (PLAYER_STATE_LOCK) {
                        if (!isSessionUsable(targetSession)) return false;
                        player = initializePlayer(retryFile);
                        ownedPlayer = player;
                        targetSession.player = ownedPlayer;
                        activePlayer = ownedPlayer;
                        activePlayer.play();
                    }
                } catch (Exception retryFailure) {
                    if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                        handlePlayerInitializationError(song, retryFailure);
                    }
                    return false;
                }
            }

            if (!isSessionUsable(targetSession)) return false;
            playing.set(true);
            final AudioPlayer callbackPlayer = activePlayer;
            callbackPlayer.setAfterPlayed(() -> {
                // 已被下一首复用/替换的播放器回调不能结束新会话。
                if (ownedPlayer == callbackPlayer && isSessionUsable(targetSession)) {
                    this.notifyWaitLock();
                }
            });
            return true;
        }

        private void preloadNextCover() {
            if (curIdx + 1 < playList.size()) {
                loadMusicCover(playList.get(curIdx + 1));
            }
        }

        private void updateCurrentIndex() {
            updateCurIdx();
        }

        private File getMusicFile(Tuple<String, String> playUrl, Music song) {
            String url = playUrl.getA();
            String reportedType = normalizeReportedAudioContainer(playUrl.getB());
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("Music URL is empty");
            }

            // CDN extensions and API-reported types are advisory only. The cache is named from
            // the downloaded bytes below, which also lets M4A/AAC fall back safely when the API
            // reports a generic or incorrect MIME type.
            try {
                return getCachedOrTempFile(url, reportedType, song);
            } catch (RuntimeException primaryFailure) {
                return getNeteaseStandardFallbackFile(song, url, primaryFailure);
            }
        }

        /**
         * A URL can be syntactically valid while serving a DASH video or another non-audio MP4
         * payload. Once the byte-level validation rejects such a primary response, retry the
         * existing NetEase standard-MP3 endpoint before treating the song as unplayable.
         */
        private File getNeteaseStandardFallbackFile(Music song, String failedUrl, RuntimeException primaryFailure) {
            if (song == null || !song.isNetease()) {
                throw primaryFailure;
            }

            Tuple<String, String> standardPlayUrl = song.getStandardMp3PlayUrl();
            if (standardPlayUrl == null || standardPlayUrl.getA() == null
                    || standardPlayUrl.getA().trim().isEmpty()
                    || standardPlayUrl.getA().equals(failedUrl)) {
                throw primaryFailure;
            }

            try {
                System.err.println("[NCM] Primary response failed byte-level audio validation for "
                        + song.getStableKey() + "; retrying standard MP3.");
                return getCachedOrTempFile(standardPlayUrl.getA(),
                        normalizeReportedAudioContainer(standardPlayUrl.getB()), song);
            } catch (RuntimeException standardFailure) {
                primaryFailure.addSuppressed(standardFailure);
                throw primaryFailure;
            }
        }
        /**
         * The format advertised by a CDN URL is not always the actual container returned by the
         * server. In particular, some lossless Netease URLs have been observed to return FLAC
         * bytes while reporting an MP3 type. SoundFile selects its decoder by extension, so the
         * cache extension must follow the downloaded bytes rather than the reported URL type.
         */
        private File getCachedOrTempFile(String playUrl, String type, Music song) {
            File musicCacheDir = new File("MusicCache");
            if (!musicCacheDir.exists() && !musicCacheDir.mkdirs()) {
                throw new IllegalStateException("Unable to create music cache directory: " + musicCacheDir.getAbsolutePath());
            }

            String cacheKey = song.getStableKey() + "_" + quality.getQuality();
            File cachedMusic = findCachedAudioFile(musicCacheDir, cacheKey, type);
            if (cachedMusic != null) {
                return resolvePlayableAudioFile(cachedMusic);
            }

            // Never expose a partially downloaded file to the decoder or cache lookup.
            File temporaryMusic = new File(musicCacheDir, cacheKey + ".download");
            if (temporaryMusic.exists() && !temporaryMusic.delete()) {
                throw new IllegalStateException("Unable to replace incomplete music download: " + temporaryMusic.getName());
            }

            downloadMusic(playUrl, temporaryMusic);
            if (!temporaryMusic.isFile() || temporaryMusic.length() < 4L) {
                throw new IllegalStateException("music download failed or was interrupted: " + temporaryMusic.getName());
            }

            String actualType = detectAudioContainer(temporaryMusic);
            if (!isSupportedAudioContainer(actualType)) {
                temporaryMusic.delete();
                throw new IllegalStateException("Downloaded audio has an unsupported or invalid container: " + cacheKey);
            }

            File music = new File(musicCacheDir, cacheKey + "." + actualType);
            if (music.exists()) {
                String existingType = detectAudioContainer(music);
                if (actualType.equals(existingType)) {
                    temporaryMusic.delete();
                } else {
                    music.delete();
                    moveCacheFile(temporaryMusic, music);
                }
            } else {
                moveCacheFile(temporaryMusic, music);
            }

            removeOtherQualityCaches(musicCacheDir, song.getStableKey(), quality.getQuality());
            return resolvePlayableAudioFile(music);
        }

        /**
         * JSyn can directly stream MP3, FLAC and WAV. AAC/ADTS and explicitly audio-branded M4A
         * streams are decoded once into a validated WAV sidecar. Generic MP4 payloads are detected
         * from their file header but never transcoded: they can be video-only, DRM-protected or
         * otherwise unsuitable for this player, so the caller can use its existing NetEase MP3
         * fallback instead.
         */
        private File resolvePlayableAudioFile(File sourceFile) {
            String container = detectAudioContainer(sourceFile);
            if (!isSupportedAudioContainer(container)) {
                throw new IllegalStateException("Cached audio has an unsupported or invalid container: "
                        + sourceFile.getName());
            }
            if (isMp4Container(container)) {
                discardLegacyDecodedWav(sourceFile);
                DownloadDynamicIsland.showUnsupportedMp4Container(sourceFile.getName());
                throw new UnsupportedMp4ContainerException(sourceFile.getName());
            }
            if (!requiresAacDecode(container)) {
                return sourceFile;
            }

            File decodedFile = getDecodedWavCacheFile(sourceFile);
            if (isReusableDecodedWav(sourceFile, decodedFile)) {
                return decodedFile;
            }
            if (decodedFile.exists() && !decodedFile.delete()) {
                throw new IllegalStateException("Unable to replace invalid decoded audio cache: "
                        + decodedFile.getName());
            }

            long transcodeStartedAt = System.currentTimeMillis();
            DownloadDynamicIsland.beginTranscode(sourceFile.getName(), decodedFile.getName());
            try {
                AacAudioDecoder.decodeToWav(sourceFile, container, decodedFile,
                        new AacAudioDecoder.ProgressListener() {
                            @Override
                            public void onProgress(double progress) {
                                DownloadDynamicIsland.updateTranscodeProgress(progress);
                            }
                        });
                if (!isReusableDecodedWav(sourceFile, decodedFile)) {
                    throw new IOException("Decoded AAC cache is invalid: " + sourceFile.getName());
                }
            } catch (Exception exception) {
                DownloadDynamicIsland.cancelTranscode();
                if (decodedFile.exists()) {
                    decodedFile.delete();
                }
                if (sourceFile.exists() && !sourceFile.delete()) {
                    System.err.println("[NCM] Unable to remove undecodable AAC cache: " + sourceFile.getName());
                }
                throw new IllegalStateException("Unable to decode AAC audio into a playable WAV cache", exception);
            }
            DownloadDynamicIsland.finishTranscode(sourceFile.getName(), decodedFile.getName(),
                    System.currentTimeMillis() - transcodeStartedAt);
            return decodedFile;
        }

        private void discardLegacyDecodedWav(File sourceFile) {
            File decodedFile = getDecodedWavCacheFile(sourceFile);
            if (decodedFile.exists() && !decodedFile.delete()) {
                System.err.println("[NCM] Unable to remove legacy MP4 decoded cache: " + decodedFile.getName());
            }
        }
        private File getDecodedWavCacheFile(File sourceFile) {
            String name = sourceFile.getName();
            int extensionIndex = name.lastIndexOf('.');
            String baseName = extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
            return new File(sourceFile.getParentFile(), baseName + ".decoded.wav");
        }

        private boolean isReusableDecodedWav(File sourceFile, File decodedFile) {
            return decodedFile.isFile()
                    && decodedFile.length() > 44L
                    && decodedFile.lastModified() >= sourceFile.lastModified()
                    && "wav".equals(detectAudioContainer(decodedFile));
        }

        private File findCachedAudioFile(File musicCacheDir, String cacheKey, String reportedType) {
            List<String> candidateTypes = new ArrayList<>();
            if (isSupportedAudioContainer(reportedType)) {
                candidateTypes.add(reportedType);
            }
            for (String supportedType : SUPPORTED_AUDIO_CONTAINERS) {
                if (!candidateTypes.contains(supportedType)) {
                    candidateTypes.add(supportedType);
                }
            }

            for (String candidateType : candidateTypes) {
                File candidate = new File(musicCacheDir, cacheKey + "." + candidateType);
                if (!candidate.isFile()) {
                    continue;
                }

                String actualType = detectAudioContainer(candidate);
                if (!isSupportedAudioContainer(actualType)) {
                    candidate.delete();
                    continue;
                }

                File normalized = new File(musicCacheDir, cacheKey + "." + actualType);
                if (candidate.equals(normalized)) {
                    return candidate;
                }

                if (normalized.isFile()) {
                    String normalizedType = detectAudioContainer(normalized);
                    if (actualType.equals(normalizedType)) {
                        candidate.delete();
                        return normalized;
                    }
                    normalized.delete();
                }

                moveCacheFile(candidate, normalized);
                return normalized;
            }
            return null;
        }

        private void removeOtherQualityCaches(File musicCacheDir, String stableKey, String currentQuality) {
            MultiThreadingUtil.runAsync(() -> {
                File[] cacheFiles = musicCacheDir.listFiles();
                if (cacheFiles == null) {
                    return;
                }

                String allQualitiesPrefix = stableKey + "_";
                String currentQualityPrefix = allQualitiesPrefix + currentQuality;
                for (File file : cacheFiles) {
                    if (file.getName().startsWith(allQualitiesPrefix) && !file.getName().startsWith(currentQualityPrefix)) {
                        file.delete();
                    }
                }
            });
        }

        private AudioPlayer initializePlayer(File musicFile) {
            AudioPlayer player = CloudMusic.player;
            if (player == null) {
                player = new AudioPlayer(musicFile);
                player.setVolume(MuoniumPlayerExtension.getInstance().musicInfo.volume.getValue().floatValue());
                CloudMusic.player = player;
            } else {
                player.setAudio(musicFile);
            }
            return player;
        }

        private void notifyWaitLock() {
            playing.set(false);
        }

        private void loadMusicCover(Music song) {
            CloudMusic.loadMusicCover(song);
        }

        private void updateCurIdx() {
            if (personalFmActive) {
                // FM batches are sequential and never loop, reshuffle, or repeat.
                if (dontAdd) dontAdd = false;
                else curIdx++;
                return;
            }
            if (playMode == PlayMode.LoopSingle) {
                // 单曲循环只影响自然播放结束；用户手动上一首/下一首仍可正常切歌。
                if (dontAdd) {
                    dontAdd = false;
                }

                if (curIdx < 0 || curIdx >= playList.size()) {
                    curIdx = 0;
                }
                return;
            }

            boolean changedByPlayerControl = dontAdd;
            dontAdd = false;
            if (!changedByPlayerControl) {
                curIdx++;
            }

            if (playMode == PlayMode.Random) {
                if (curIdx >= playList.size()) {
                    reshuffleRandomQueueForNextCycle(songs);
                    curIdx = 0;
                }
                return;
            }

            if (playMode == PlayMode.LoopInList && curIdx >= playList.size()) {
                curIdx = 0;
            }
        }

        private void sleep(int millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void loadMusicCover(Music music) {
        loadMusicCover(music, false);
    }

    public static void loadMusicCover(Music music, boolean forceReload) {
        Location musicCover = music.getCoverLocation();
        Location musicCoverSmall = music.getSmallCoverLocation();
        Location musicCoverBlur = music.getBlurredCoverLocation();
        TextureManager textureManager = TextureManager.getInstance();

        if (shouldLoadCover(textureManager, musicCover, forceReload)) {
            loadMainCoverAsync(music, musicCover, musicCoverBlur);
        }

        if (shouldLoadCover(textureManager, musicCoverSmall, forceReload)) {
            loadSmallCoverAsync(music, musicCoverSmall);
        }
    }

    private static boolean shouldLoadCover(TextureManager textureManager, Location coverLocation, boolean forceReload) {
        return textureManager.getTexture(coverLocation) == null || forceReload;
    }

    private static void loadMainCoverAsync(Music music, Location musicCover, Location musicCoverBlur) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                @Cleanup
                InputStream coverStream = HttpUtils.downloadStream(music.getCoverUrl(320), 5);
                byte[] imageData = IOUtils.toByteArray(coverStream);

                BufferedImage coverImage = DynamicTexture.readImage(new ByteArrayInputStream(imageData));

                if (coverImage != null) {
                    loadCoverTextures(coverImage, musicCover, musicCoverBlur);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private static void loadCoverTextures(BufferedImage coverImage, Location musicCover, Location musicCoverBlur) {
        Textures.loadTexture(musicCover, coverImage);

        MultiThreadingUtil.runAsync(() -> {
            BufferedImage inputImage = new BufferedImage(coverImage.getWidth(), coverImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
            inputImage.setRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(),
                    coverImage.getRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(), null, 0, coverImage.getWidth()),
                    0, coverImage.getWidth());

            // 创建高斯模糊之后的歌曲封面, 目前仅在播放器的歌词界面使用
            BufferedImage blurredImage = gaussianBlur(inputImage, 31);
            Textures.loadTexture(musicCoverBlur, blurredImage);
        });
    }

    private static void loadSmallCoverAsync(Music music, Location musicCoverSmall) {
        MultiThreadingUtil.runAsync(() -> {
            InputStream smallCoverStream = HttpUtils.downloadStream(music.getCoverUrl(128), 5);
            BufferedImage smallCoverImage = DynamicTexture.readImage(smallCoverStream);
            Textures.loadTexture(musicCoverSmall, smallCoverImage);
        });
    }

    private static final Kernel GAUSSIAN_KERNEL = new Kernel(41, 41, GaussianKernel.generate(41));

    public static BufferedImage gaussianBlur(BufferedImage imgIn, int blur) {
        Map<RenderingHints.Key, Object> map = new HashMap<>();
        map.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        map.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        map.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        RenderingHints hints = new RenderingHints(map);

        ConvolveOp op = new ConvolveOp(GAUSSIAN_KERNEL, ConvolveOp.EDGE_NO_OP, hints);

        BufferedImage filtered = op.filter(imgIn, null);

        BufferedImage output = new BufferedImage(filtered.getWidth(), filtered.getHeight(), filtered.getType());
        Graphics2D graphics = (Graphics2D) output.getGraphics();
        graphics.setRenderingHints(map);
        graphics.drawImage(filtered, -blur, -blur, filtered.getWidth() + blur * 2, filtered.getHeight() + blur * 2, null);

        return output;
    }

    @SneakyThrows
    private static File convertFlacToWav(File flacIn, File destFile) {

        @Cleanup
        FileOutputStream os = new FileOutputStream(destFile);

        WavWriter ww = new WavWriter(os);

        FLACDecoder fd = new FLACDecoder(Files.newInputStream(flacIn.toPath()));
        fd.addPCMProcessor(new PCMProcessor() {
            @Override
            public void processStreamInfo(StreamInfo info) {
                try {
                    ww.writeHeader(info);
                } catch (IOException e) {
                    e.printStackTrace();
                    MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
                    DownloadDynamicIsland.cancelDownload();
                    destFile.delete();
                }
            }

            @Override
            public void processPCM(ByteData pcm) {
                try {
                    ww.writePCM(pcm);
                } catch (IOException e) {
                    e.printStackTrace();
                    MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
                    DownloadDynamicIsland.cancelDownload();
                    destFile.delete();
                }
            }
        });
        fd.decode();

        return destFile;
    }

    @SneakyThrows
    private static File convertMp3ToWav(File mp3In, File destFile) {

        Converter converter = new Converter();
        converter.convert(Files.newInputStream(mp3In.toPath()), destFile.getAbsolutePath(), null, null);

        return destFile;
    }

    private static final List<String> SUPPORTED_AUDIO_CONTAINERS =
            Arrays.asList("flac", "wav", "mp3", "aac", "m4a", "mp4");

    private static boolean isSupportedAudioContainer(String container) {
        return container != null && SUPPORTED_AUDIO_CONTAINERS.contains(container);
    }

    private static boolean requiresAacDecode(String container) {
        return "aac".equals(container) || "m4a".equals(container);
    }

    private static boolean isMp4Container(String container) {
        return "mp4".equals(container);
    }

    private static final class UnsupportedMp4ContainerException extends IllegalStateException {
        private UnsupportedMp4ContainerException(String sourceName) {
            super("Detected unsupported MP4 container from file header: " + sourceName);
        }
    }

    /**
     * Normalizes an API MIME type/extension, but callers must still inspect the file bytes before
     * playing it. This deliberately accepts unknown values as null so a valid CDN response with
     * a bad Content-Type can still be recognized after it is downloaded.
     */
    private static String normalizeReportedAudioContainer(String reportedType) {
        if (reportedType == null) {
            return null;
        }
        String type = reportedType.trim().toLowerCase();
        int parameterIndex = type.indexOf(';');
        if (parameterIndex >= 0) {
            type = type.substring(0, parameterIndex).trim();
        }
        if (type.startsWith("audio/")) {
            type = type.substring("audio/".length());
        }
        if ("mpeg".equals(type) || "mpga".equals(type) || "x-mp3".equals(type)) {
            return "mp3";
        }
        if ("x-wav".equals(type) || "wave".equals(type)) {
            return "wav";
        }
        if ("x-flac".equals(type)) {
            return "flac";
        }
        if ("adts".equals(type) || "x-aac".equals(type)) {
            return "aac";
        }
        if ("mp4".equals(type) || "mp4a".equals(type) || "m4a".equals(type)) {
            return "m4a";
        }
        return isSupportedAudioContainer(type) ? type : null;
    }

    /**
     * Identifies the real container from its bytes rather than trusting a URL suffix or API type.
     * AAC ADTS must be recognized before an MPEG frame: both begin with an 0xFFF sync word.
     */
    private static String detectAudioContainer(File file) {
        if (file == null || !file.isFile() || file.length() < 4L) {
            return null;
        }

        byte[] header = new byte[64];
        try (InputStream input = Files.newInputStream(file.toPath())) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }

            if (hasAscii(header, offset, 0, "fLaC")) {
                return "flac";
            }
            if (hasAscii(header, offset, 0, "RIFF") && hasAscii(header, offset, 8, "WAVE")) {
                return "wav";
            }
            if (hasAscii(header, offset, 0, "OggS")) {
                return "ogg";
            }
            if (isAsfHeader(header, offset)) {
                return "asf";
            }
            String isoBaseMediaContainer = detectIsoBaseMediaContainer(header, offset);
            if (isoBaseMediaContainer != null) {
                return isoBaseMediaContainer;
            }
            if (hasAscii(header, offset, 0, "ID3")) {
                return "mp3";
            }
            if (isAdtsAacHeader(header, offset)) {
                return "aac";
            }
            if (isMpegAudioHeader(header, offset)) {
                return "mp3";
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static boolean hasAscii(byte[] bytes, int length, int start, String value) {
        if (bytes == null || length < start + value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if ((bytes[start + index] & 0xFF) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Classifies ISO-BMFF files by the ftyp box itself. A generic MP4/video payload is not
     * playable audio merely because it begins with ftyp; only an explicitly audio-branded M4A
     * file is allowed onto the AAC decoder path.
     */
    private static String detectIsoBaseMediaContainer(byte[] header, int length) {
        if (!hasAscii(header, length, 4, "ftyp")) {
            return null;
        }
        long boxSize = ((long) (header[0] & 0xFF) << 24)
                | ((long) (header[1] & 0xFF) << 16)
                | ((long) (header[2] & 0xFF) << 8)
                | (header[3] & 0xFF);
        if (boxSize < 16L || boxSize > length) {
            return "mp4";
        }

        // Major brand is at byte 8; compatible brands start at byte 16.
        for (int brandOffset = 8; brandOffset + 4 <= boxSize; brandOffset += 4) {
            if (hasIsoAudioBrand(header, length, brandOffset)) {
                return "m4a";
            }
        }
        return "mp4";
    }

    private static boolean hasIsoAudioBrand(byte[] header, int length, int offset) {
        return hasAscii(header, length, offset, "M4A ")
                || hasAscii(header, length, offset, "M4B ")
                || hasAscii(header, length, offset, "M4P ")
                || hasAscii(header, length, offset, "mp4a");
    }

    private static boolean isAdtsAacHeader(byte[] header, int length) {
        if (length < 7 || (header[0] & 0xFF) != 0xFF || (header[1] & 0xF0) != 0xF0) {
            return false;
        }
        // ADTS has a zero Layer field; MPEG audio has a non-zero Layer field.
        if ((header[1] & 0x06) != 0) {
            return false;
        }
        int frequencyIndex = (header[2] >>> 2) & 0x0F;
        if (frequencyIndex == 0x0F) {
            return false;
        }
        int frameLength = ((header[3] & 0x03) << 11)
                | ((header[4] & 0xFF) << 3)
                | ((header[5] >>> 5) & 0x07);
        return frameLength >= 7;
    }

    private static boolean isMpegAudioHeader(byte[] header, int length) {
        if (length < 4 || (header[0] & 0xFF) != 0xFF || (header[1] & 0xE0) != 0xE0) {
            return false;
        }
        int layer = (header[1] >>> 1) & 0x03;
        int bitrateIndex = (header[2] >>> 4) & 0x0F;
        return layer != 0 && bitrateIndex != 0 && bitrateIndex != 0x0F;
    }

    private static boolean isAsfHeader(byte[] header, int length) {
        int[] asfHeaderGuid = {0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
                0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C};
        if (length < asfHeaderGuid.length) {
            return false;
        }
        for (int index = 0; index < asfHeaderGuid.length; index++) {
            if ((header[index] & 0xFF) != asfHeaderGuid[index]) {
                return false;
            }
        }
        return true;
    }

    private static void moveCacheFile(File source, File destination) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to finalize music cache file: " + source.getName(), e);
        }
    }

    @SneakyThrows
    private static void downloadMusic(String playUrl, File music) {

        MuoniumPlayerExtension.getInstance().musicInfo.downloading = true;
        MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = 0;
        MuoniumPlayerExtension.getInstance().musicInfo.downloadSpeed = "0 b/s";
        DownloadDynamicIsland.beginDownload();

        try {
            InputStream stream = new WrappedInputStream(HttpUtils.get(playUrl, null), new WrappedInputStream.ProgressListener() {

                tritium.utils.timing.Timer timer = new tritium.utils.timing.Timer();

                @Override
                public void onProgress(double progress) {
                    MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = progress;
                    DownloadDynamicIsland.updateProgress(progress);

                    if (progress >= 1) {
                        MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
                    }
                }

                final long kilo = 1024;
                final long mega = kilo * kilo;
                final long giga = mega * kilo;
                final long tera = giga * kilo;

                String getSize(long size) {
                    String s;
                    double kb = (double) size / kilo;
                    double mb = kb / kilo;
                    double gb = mb / kilo;
                    double tb = gb / kilo;
                    if (size < kilo) {
                        s = size + " Bytes";
                    } else if (size < mega) {
                        s = String.format("%.2f", kb) + " KB";
                    } else if (size < giga) {
                        s = String.format("%.2f", mb) + " MB";
                    } else if (size < tera) {
                        s = String.format("%.2f", gb) + " GB";
                    } else {
                        s = String.format("%.2f", tb) + " TB";
                    }
                    return s;
                }

                int lastBytesRead = 0;

                @Override
                public void bytesRead(int bytesRead) {

                    int checkDelay = 500;

                    if (timer.isDelayed(checkDelay)) {
                        timer.reset();

                        int diff = (bytesRead - lastBytesRead) * (1000 / checkDelay);

                        String speed = this.getSize(diff) + "/s";
                        MuoniumPlayerExtension.getInstance().musicInfo.downloadSpeed = speed;
                        DownloadDynamicIsland.updateSpeed(speed);

                        lastBytesRead = bytesRead;
                    }

                }
            });

            OutputStream os = Files.newOutputStream(music.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            writeTo(stream, os);

            os.close();
            MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = 1.0;
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.finishDownload();

        } catch (Throwable t) {
            t.printStackTrace();

            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.cancelDownload();

            music.delete();
        }
    }

    @SneakyThrows
    public static void writeTo(InputStream src, OutputStream dest) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = src.read(buffer)) != -1) {
            dest.write(buffer, 0, len);
        }
        dest.flush();
    }

    public static void initLyrics(JsonObject rawLyricData, Music music, List<LyricLine> parsedLyrics) {
        resetLyricFlags();
        if (rawLyricData != null) {
            detectTranslations(rawLyricData);
        }
        if (parsedLyrics != null) {
            for (LyricLine line : parsedLyrics) {
                if (line == null) continue;
                if (line.translationText != null && !line.translationText.trim().isEmpty()) hasTransLyrics = true;
                if (line.romanizationText != null && !line.romanizationText.trim().isEmpty()) hasRomanization = true;
            }
        }

        synchronized (lyrics) {
            updateLyricsList(parsedLyrics == null ? Collections.emptyList() : parsedLyrics);
            currentLyric = lyrics.get(0);
            haveNoWords = lyricsHaveNoWords();
            addLongBreaks();
        }

        MusicLyricsPanel.updateLyricPositionsImmediate(NCMScreen.getInstance().getPanelWidth() * MusicLyricsPanel.getLyricWidthFactor());
    }

    /**
     * 两阶段提交的第二阶段：只在 Minecraft 主线程调用，把已验证的歌词结果提交为当前 Timeline。
     * 提交前再做一次 sessionId + songId 双重校验，并用当前 AudioPlayer 的真实 positionMs 对齐
     * currentLyric（歌词可先到或后到，均以声音时钟为准）。
     */
    public static void applyLyricTimeline(PlaybackSession session, JsonObject rawLyricData, List<LyricLine> parsedLyrics) {
        Music current = currentlyPlaying;
        if (!session.isActive() || current == null || !current.getStableKey().equals(session.trackKey)) {
            return;  // 提交瞬间已切歌：丢弃旧歌词，不污染新状态
        }

        initLyrics(rawLyricData, current, parsedLyrics);

        AudioPlayer p = session.player != null ? session.player : player;
        if (p != null) {
            updateCurrentLyric(p.getCurrentTimeMillis());
            // 歌词迟到时按真实 position 立即重新居中，避免先按首句定位再跳变
            MusicLyricsPanel.updateLyricPositionsImmediate(NCMScreen.getInstance().getPanelWidth() * MusicLyricsPanel.getLyricWidthFactor());
        }
    }

    /**
     * 不可变播放快照：渲染一帧只获取一次，本帧歌词、进度条、逐字高亮全部使用同一快照，
     * 避免一帧内跨线程读取到不同歌曲 / 不同 position。
     */
    public static class PlaybackSnapshot {
        public final long sessionId;
        public final long songId;
        public final long positionMs;
        public final long durationMs;
        public final boolean playing;
        public final AudioPlayer player;
        public final Music music;

        public PlaybackSnapshot(long sessionId, long songId, long positionMs, long durationMs, boolean playing, AudioPlayer player, Music music) {
            this.sessionId = sessionId;
            this.songId = songId;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
            this.player = player;
            this.music = music;
        }
    }

    public static PlaybackSnapshot getSnapshot() {
        AudioPlayer p = player;
        Music m = currentlyPlaying;
        PlaybackSession s = currentSession;
        long sid = s == null ? -1L : s.sessionId;
        long songId = m == null ? -1L : m.getId();
        long positionMs = (p == null || m == null) ? 0L : Math.round(p.getCurrentTimeMillis());
        long durationMs = (p == null || m == null) ? 0L : Math.round(p.getTotalTimeMillis());
        boolean playing = (p != null && m != null) && !p.isFinished();
        return new PlaybackSnapshot(sid, songId, positionMs, durationMs, playing, p, m);
    }

    private static void resetLyricFlags() {
        hasTransLyrics = false;
        hasRomanization = false;
    }

    private static void updateLyricsList(List<LyricLine> parsedLyrics) {
        lyrics.clear();
        lyrics.addAll(parsedLyrics);

        if (lyrics.isEmpty()) {
            lyrics.add(new LyricLine(0L, "暂无歌词"));
        }
    }

    private static void detectTranslations(JsonObject lyric) {
        if (hasLyricsType(lyric, "tlyric") || hasLyricsType(lyric, "ytlrc")) hasTransLyrics = true;
        if (hasLyricsType(lyric, "romalrc") || hasLyricsType(lyric, "yromalrc")) hasRomanization = true;
    }

    private static boolean hasLyricsType(JsonObject lyric, String type) {
        if (lyric.has(type) && lyric.get(type).isJsonObject()) {
            JsonObject lyricTypeObj = lyric.get(type).getAsJsonObject();
            return lyricTypeObj.has("lyric") && !lyricTypeObj.get("lyric").getAsString().isEmpty();
        }
        return false;
    }

    /**
     * 为歌词添加长间隔时的 "● ● ●"
     */
    private static void addLongBreaks() {
        final long longBreaksDuration = 3000L;

        if (haveNoWords) {
            // 如果不为逐字歌词的话只在开头添加长间隔
            addInitialBreakIfNeeded(longBreaksDuration);
            return;
        }

        addBreaksBetweenLyrics(longBreaksDuration);
    }

    /**
     * 歌词是否不为逐字歌词
     * @return true 表示不为逐字歌词
     */
    private static boolean lyricsHaveNoWords() {
        return lyrics.stream().allMatch(l -> l.words.isEmpty());
    }

    private static void addInitialBreakIfNeeded(long duration) {
        long firstTimestamp = lyrics.get(0).getTimestamp();
        if (firstTimestamp >= duration) {
            addBreakLine(0L, firstTimestamp);
        }
    }

    private static void addBreaksBetweenLyrics(long duration) {
        long lastTimestamp = 0L;
        List<LyricLine> breaksToAdd = new ArrayList<>();

        for (LyricLine line : lyrics) {
            long lineDuration = line.duration;
            long gap = line.getTimestamp() - lastTimestamp;

            if (gap >= duration) {
                breaksToAdd.add(createBreakLine(lastTimestamp, gap));
            }

            lastTimestamp = line.getTimestamp() + lineDuration;
        }

        addAndSortBreaks(breaksToAdd);
    }

    private static LyricLine createBreakLine(long timestamp, long duration) {
        LyricLine line = new LyricLine(timestamp, "● ● ●");
        line.isBreakLine = true;
        line.words.add(new LyricLine.Word("● ● ●", timestamp, duration));
        return line;
    }

    private static void addBreakLine(long timestamp, long duration) {
        lyrics.add(createBreakLine(timestamp, duration));
        lyrics.sort(Comparator.comparingLong(LyricLine::getTimestamp));
    }

    private static void addAndSortBreaks(List<LyricLine> breaks) {
        lyrics.addAll(breaks);
        lyrics.sort(Comparator.comparingLong(LyricLine::getTimestamp));
    }

    private static long getLyricDuration(LyricLine line) {
        return line.duration;
    }

    /**
     * 更新当前歌词行
     * @param songProgress 歌曲进度 (ms)
     */
    public static void updateCurrentLyric(float songProgress) {
        LyricLine previousLyric = currentLyric;
        currentLyric = findCurrentLyric(songProgress);

        if (previousLyric != currentLyric) {
            resetLyricPositionUpdate();
        }
    }

    static final float JUMP_TO_NEXT_MILLIS = 300.0f;

    static boolean canJumpToNextEarly(double songProgress, LyricLine lyric) {
        if (lyric == null || lyric.words.isEmpty())
            return false;

        if (lyric.duration < JUMP_TO_NEXT_MILLIS)
            return false;

        return true;
    }

    public static LyricLine findCurrentLyric(double songProgress) {
        for (int i = 0; i < lyrics.size(); i++) {
            LyricLine lyric = lyrics.get(i);
            LyricLine prev = i > 0 ? lyrics.get(i - 1) : null;

            if (!haveNoWords
                    && !lyric.isBreakLine
                    && lyric.getTimestamp() > songProgress
                    && lyric.getTimestamp() - songProgress <= JUMP_TO_NEXT_MILLIS
                    && canJumpToNextEarly(songProgress, prev)) {
                return lyric;
            }

            if (lyric.getTimestamp() > songProgress) {
                // 只基于当前 Timeline 计算，禁止回退到旧 Session 的 currentLyric（避免跨歌污染）
                return i > 0 ? lyrics.get(i - 1) : lyrics.get(0);
            }

            if (i == lyrics.size() - 1) {
                return lyric;
            }
        }
        return lyrics.isEmpty() ? null : lyrics.get(0);
    }

    public static void resetLyricPositionUpdate() {
        lyrics.forEach(l -> {
            l.shouldUpdatePosition = false;
            l.delayTimer.reset();
        });
    }

    public static void resetLyricStatus() {
        lyrics.forEach(l -> {
            l.shouldUpdatePosition = false;
            l.delayTimer.reset();

            for (LyricLine.Word word : l.words) {
                Arrays.fill(word.emphasizes, 0);
            }

            l.markDirty();
        });
    }

    public static void setLyricsProgress(float progress) {
        if (lyrics.isEmpty()) return;

        try {
            resetLyricDisplayStates();
            updateCurrentLyric(progress);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void resetLyricDisplayStates() {
        resetAllLyricsState();
        resetWordStates();
    }

    private static void resetAllLyricsState() {
        for (LyricLine lyric : lyrics) {
            lyric.scrollWidth = 0;
            lyric.offsetX = 0;
            lyric.offsetY = Double.MIN_VALUE;
            lyric.targetOffsetX = 0;
        }
    }

    private static void resetWordStates() {
        for (LyricLine lyric : lyrics) {
            for (LyricLine.Word word : lyric.words) {
                word.alpha = 0.0f;
                word.progress = 0.0;
            }
        }
    }

    public static String getSecondaryLyrics(LyricLine lyricLine) {
        if (hasTransLyrics) {
            return getTranslationOrRomanizationText(lyricLine);
        }

        if (hasRomanization) {
            return getRomanizationTextIfEnabled(lyricLine);
        }

        return "";
    }

    private static String getTranslationOrRomanizationText(LyricLine lyricLine) {
        boolean showRoman = MuoniumPlayerExtension.getInstance().musicLyrics.showRoman.getValue();

        if (!showRoman) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getTranslationText());
        }

        if (hasRomanization) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getRomanizationText());
        }

        return StringUtils.returnEmptyStringIfNull(lyricLine.getTranslationText());
    }

    private static String getRomanizationTextIfEnabled(LyricLine lyricLine) {
        if (MuoniumPlayerExtension.getInstance().musicLyrics.showRoman.getValue()) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getRomanizationText());
        }
        return "";
    }

    public static boolean hasSecondaryLyrics() {
        boolean hasAvailableLyrics = hasTransLyrics || hasRomanization;
        boolean showTranslationEnabled = MuoniumPlayerExtension.getInstance().musicLyrics.showTranslation.getValue();
        return hasAvailableLyrics && showTranslationEnabled;
    }

    public static void loadLyric(Music music, PlaybackSession session) {
        loadLyric(music, session, null);
    }

    private static void loadLyric(Music music, PlaybackSession session, File playbackFile) {
        final long songId = music.getId();
        final String trackKey = music.getStableKey();
        final File embeddedLyricFile = music.isCloudSong() ? resolveEmbeddedLyricFile(playbackFile) : null;

        MultiThreadingUtil.runAsync(() -> {
            JsonObject rawJson = new JsonObject();
            List<LyricLine> parsed = Collections.emptyList();
            boolean cloudLyricsLoaded = false;

            // 云盘歌曲的歌词不一定能通过普通 lyricNew(songId) 查询到。
            // api-enhanced 提供了专用的 /cloud/lyric/get 接口，使用当前账号 UID
            // 与云盘歌曲 ID 获取文件元数据中的 LYRICS 歌词。只有解析出有效时间轴
            // 才提交结果，失败时继续走内嵌标签、Cadence 和原有回退链。
            if (music.isCloudSong() && profile != null && profile.getId() > 0L) {
                try {
                    JsonObject cloudJson = normalizeCloudLyricResponse(
                            CloudMusicApi.cloudLyricGet(profile.getId(), songId).toJsonObject());
                    List<LyricLine> cloudLyrics = LyricParser.parse(cloudJson);
                    if (!cloudLyrics.isEmpty()) {
                        rawJson = cloudJson;
                        parsed = cloudLyrics;
                        cloudLyricsLoaded = true;
                        System.out.println("[Music] Loaded cloud-drive lyrics for " + trackKey);
                    }
                } catch (Throwable throwable) {
                    System.err.println("[Music] Cloud-drive lyric API failed for " + trackKey + ": "
                            + throwable.getMessage());
                }
            }

            // 私人网盘曲目不一定存在网易云可查询的 lyric id。音频文件中的 USLT、Vorbis
            // comments 或 M4A ©lyr 是这类曲目最接近原文件的歌词来源，优先采用其中有效的
            // 时间轴；无法读取或不是时间歌词时才继续原有的在线回退链。
            if (!cloudLyricsLoaded && embeddedLyricFile != null) {
                try {
                    String embeddedText = EmbeddedLyricsReader.read(embeddedLyricFile);
                    if (!embeddedText.isEmpty()) {
                        JsonObject embeddedJson = createEmbeddedLyricJson(embeddedText);
                        List<LyricLine> embedded = LyricParser.parse(embeddedJson);
                        if (!embedded.isEmpty()) {
                            rawJson = embeddedJson;
                            parsed = embedded;
                            cloudLyricsLoaded = true;
                            System.out.println("[Music] Loaded embedded lyrics for cloud song " + trackKey
                                    + " from " + embeddedLyricFile.getName());
                        }
                    }
                } catch (Throwable throwable) {
                    System.err.println("[Music] Embedded lyric read failed for " + trackKey + ": "
                            + throwable.getMessage());
                }
            }

            // 首选 Cadence 统一模型，但普通 LRC 不再按字数伪造逐字时间轴。
            if (parsed.isEmpty()) {
                try {
                    top.fpsmaster.music.Lyric cadenceLyric = CadenceMusicService.getLyric(music);
                    if (cadenceLyric != null) {
                        parsed = LyricParser.fromCadence(cadenceLyric, music.getDuration(), false);
                    }
                } catch (Throwable throwable) {
                    System.err.println("[Music/Cadence] Unified lyric conversion failed for " + trackKey + ": " + throwable.getMessage());
                }
            }

            // Cadence 只拿到普通 LRC 时也继续询问网易云 lyricNew，优先采用其中真实的 YRC 逐字时间轴。
            if (music.isNetease() && !cloudLyricsLoaded && !LyricParser.hasRealWordTiming(parsed)) {
                try {
                    String string = CloudMusicApi.lyricNew(songId).toString();
                    string = string.replaceAll("[ - ]", " ");
                    rawJson = JsonUtils.toJsonObject(string);
                    List<LyricLine> fallback = LyricParser.parse(rawJson);
                    if (LyricParser.hasRealWordTiming(fallback) || parsed.isEmpty()) parsed = fallback;
                } catch (Throwable throwable) {
                    System.err.println("[NCM] Legacy lyric fallback failed for " + trackKey + ": " + throwable.getMessage());
                }
            }

            // 内置修正 YRC 是最后的真实逐字兜底；读取失败时保留前面已经得到的普通歌词。
            if (music.isNetease() && !cloudLyricsLoaded && !LyricParser.hasRealWordTiming(parsed)) {
                InputStream stream = CloudMusic.class.getResourceAsStream("/tritium/yrc/" + songId + ".yrc");
                if (stream != null) {
                    try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        writeTo(stream, baos);
                        String yrc = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                        List<LyricLine> embedded = new ArrayList<>();
                        LyricParser.parseYrc(yrc, embedded);
                        if (LyricParser.hasRealWordTiming(embedded)) parsed = embedded;
                    } catch (Throwable throwable) {
                        System.err.println("[NCM] Embedded YRC fallback failed for " + trackKey + ": " + throwable.getMessage());
                    } finally {
                        try {
                            stream.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }

            Music current = currentlyPlaying;
            if (!session.isActive() || current == null || !trackKey.equals(current.getStableKey())
                    || !trackKey.equals(session.trackKey)) {
                return;
            }

            final JsonObject committedJson = rawJson;
            final List<LyricLine> committedLyrics = parsed == null ? Collections.emptyList() : parsed;
            session.pendingLyrics = committedLyrics;
            MultiThreadingUtil.runOnMainThread(() -> applyLyricTimeline(session, committedJson, committedLyrics));
        });
    }

    private static JsonObject createEmbeddedLyricJson(String lyricText) {
        JsonObject root = new JsonObject();
        JsonObject lrc = new JsonObject();
        lrc.addProperty("lyric", lyricText);
        root.add("lrc", lrc);
        return root;
    }

    /**
     * Accepts both the direct NetEase lyric response and wrappers used by
     * enhanced API deployments (for example {data:{...}} or {result:{...}}).
     */
    private static JsonObject normalizeCloudLyricResponse(JsonObject response) {
        if (response == null) return new JsonObject();
        if (hasLyricPayload(response)) return response;

        JsonElement data = response.get("data");
        if (data != null && data.isJsonObject() && hasLyricPayload(data.getAsJsonObject())) {
            return data.getAsJsonObject();
        }

        JsonElement result = response.get("result");
        if (result != null && result.isJsonObject() && hasLyricPayload(result.getAsJsonObject())) {
            return result.getAsJsonObject();
        }
        return response;
    }

    private static boolean hasLyricPayload(JsonObject object) {
        return object.has("lrc") || object.has("yrc") || object.has("ytlrc")
                || object.has("tlyric") || object.has("romalrc");
    }

    /** Resolves the original tagged cache file when AAC/M4A playback uses a decoded WAV sidecar. */
    private static File resolveEmbeddedLyricFile(File playbackFile) {
        if (playbackFile == null || !playbackFile.isFile()) {
            return null;
        }
        String name = playbackFile.getName();
        final String decodedSuffix = ".decoded.wav";
        if (!name.endsWith(decodedSuffix)) {
            return playbackFile;
        }

        String baseName = name.substring(0, name.length() - decodedSuffix.length());
        String[] sourceExtensions = {"m4a", "aac", "mp3", "flac"};
        for (String extension : sourceExtensions) {
            File source = new File(playbackFile.getParentFile(), baseName + "." + extension);
            if (source.isFile()) {
                return source;
            }
        }
        return playbackFile;
    }

    public static String qrCodeLogin() {
        String key = CloudMusic.qrKey();

        QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);

        while (true) {

            if (Thread.currentThread().isInterrupted()) {
                return "";
            }

            JsonObject json = CloudMusicApi.loginQrCheck(key).toJsonObject();

            int code = json.get("code").getAsInt();
            if (code == 800) {
                key = CloudMusic.qrKey();

                QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);
            }

            if (code == 802) {
                if (json.has("nickname")) {
                    NCMScreen.getInstance().loginRenderer.tempUsername = json.get("nickname").getAsString();
                }

                if (json.has("avatarUrl")) {
                    String url = json.get("avatarUrl").getAsString();

                    if (!NCMScreen.getInstance().loginRenderer.avatarLoaded) {
                        NCMScreen.getInstance().loginRenderer.avatarLoaded = true;
                        MultiThreadingUtil.runAsync(() -> {
                            try (InputStream is = HttpUtils.get(url, null)) {
                                BufferedImage img = DynamicTexture.readImage(is);

                                Textures.loadTextureAsyncly(NCMScreen.getInstance().loginRenderer.tempAvatar, img);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }
            }

            if (code == 803) {

                String cookie = json.get("cookie").getAsString();

                String[] split = cookie.split(";");
                StringBuilder sb = new StringBuilder();
                for (String s : split) {
                    if (s.contains("MUSIC_U") || s.contains("__csrf")) {
                        sb.append(s).append("; ");
                    }
                }

                return sb.substring(0, sb.length() - 2);
            }

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static User getUserProfile() {
        JsonObject jsonObject = CloudMusicApi.loginStatus().toJsonObject();

        JsonObject d = jsonObject.getAsJsonObject("data");

        if ((!d.has("account") || d.get("account") instanceof JsonNull) || (!d.has("profile") || d.get("profile") instanceof JsonNull)) {
            OptionsUtil.setCookie("");
            return null;
        }

        JsonObject profile = d.getAsJsonObject("profile");

        return JsonUtils.parse(profile, User.class);
    }

    public static List<Music> search(String keyWord) {
        List<Music> cadenceResults = CadenceMusicService.search(keyWord, 50);
        if (!cadenceResults.isEmpty() || CadenceMusicService.getCurrentPlatform() == MusicPlatform.QQ) {
            return cadenceResults;
        }

        // Cadence 网络失败时仅对网易云保留原 API 兜底，QQ 不可误发到网易云搜索。
        List<Music> searchResults = new ArrayList<>();
        JsonObject searchResponse = CloudMusicApi.cloudSearch(keyWord, CloudMusicApi.SearchType.Single).toJsonObject();
        JsonArray songs = extractSongsFromResponse(searchResponse);
        if (songs != null) {
            for (JsonElement song : songs) {
                searchResults.add(JsonUtils.parse(song.getAsJsonObject(), Music.class));
            }
        }
        return searchResults;
    }

    private static JsonArray extractSongsFromResponse(JsonObject searchResponse) {
        try {
            JsonObject result = searchResponse.getAsJsonObject("result");
            return result != null ? result.getAsJsonArray("songs") : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse search response", e);
        }
    }

    public static List<Long> likeList() {
        return loadLikeList(profile);
    }

    private static List<Long> loadLikeList(User user) {
        if (user == null) {
            return new ArrayList<>();
        }
        List<Long> list = new ArrayList<>();

        JsonObject json = CloudMusicApi.likeList(user.getId()).toJsonObject();
        JsonArray ids = json.getAsJsonArray("ids");
        if (ids == null) {
            return list;
        }
        for (JsonElement id : ids) {
            list.add(id.getAsLong());
        }

        return list;
    }

    public static String qrKey() {
        JsonObject json = CloudMusicApi.loginQrKey().toJsonObject();
        return json.getAsJsonObject("data").get("unikey").getAsString();
    }

}
