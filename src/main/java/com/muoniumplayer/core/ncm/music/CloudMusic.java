package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import today.opai.api.enums.EnumChatColor;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.screens.ncm.MusicLyricsPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.StringUtils;
import com.muoniumplayer.core.utils.other.WrappedInputStream;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * <p>
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

    /**
     * 当前播放会话（volatile：播放线程写、主线程读，保证跨线程可见性）。
     */
    public static volatile PlaybackSession currentSession = null;

    public static volatile AudioPlayer player;
    private static final Object PLAYER_STATE_LOCK = new Object();
    private static final long PLAY_THREAD_JOIN_TIMEOUT_MS = 1000L;
    // 当前播放列表
    public static List<Music> playList = new ArrayList<>();
    public static int curIdx = 0;
    public static volatile Music currentlyPlaying;
    /**
     * The real NetEase playlist that started the current queue; null for searches/temporary lists.
     */
    public static volatile PlayList currentPlaylistContext;
    public static Thread playThread;

    public static volatile User profile;
    public static volatile List<PlayList> playLists;
    public static volatile List<Long> likeList;
    /**
     * IDs from /api/v1/cloud/get, used to mark the same songs wherever they appear in lists.
     */
    private static volatile Set<Long> userCloudSongIds = Collections.emptySet();

    /**
     * Prevents duplicate account refresh requests while the previous one is still running.
     */
    private static final AtomicBoolean NETEASE_REFRESHING = new AtomicBoolean(false);

    public static volatile PlayMode playMode = PlayMode.Sequential;
    /**
     * Whether the current queue belongs to the isolated personal FM session.
     */
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
        profile = NeteaseAccountRepository.getUserProfile();

        if (profile == null) {
            return;
        }

        System.out.printf("[NCM] Logged in as %s(%s)\n", profile.getName(), profile.getId());

        if (!OptionsUtil.getCookie().isEmpty()) {
            onStop();
        }

        CloudMusic.playLists = NeteaseAccountRepository.loadUserPlaylists(profile);
        System.out.printf("[NCM] Loaded %s playlists\n", playLists.size());

        likeList = NeteaseAccountRepository.loadLikeList(profile);
        Set<Long> loadedCloudSongIds = NeteaseAccountRepository.loadCloudSongIds();
        if (loadedCloudSongIds != null) {
            userCloudSongIds = loadedCloudSongIds;
            System.out.printf("[NCM] Loaded %s cloud-drive song markers%n", userCloudSongIds.size());
        }
        NCMScreen.getInstance().markDirty();
        MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().reloadCurrentPanel());
    }

    /**
     * Attempts to claim the single网易云刷新 slot.
     */
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
            User refreshedProfile = NeteaseAccountRepository.getUserProfile();
            if (refreshedProfile == null) {
                return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, "登录状态已失效");
            }

            List<PlayList> refreshedPlaylists = NeteaseAccountRepository.loadUserPlaylistsStrict(refreshedProfile);
            List<Long> refreshedLikeList = NeteaseAccountRepository.loadLikeList(refreshedProfile);
            Set<Long> refreshedCloudSongIds = NeteaseAccountRepository.loadCloudSongIds();

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

    /**
     * Returns whether a NetEase song is present in the authenticated user's cloud drive.
     */
    public static boolean isUserCloudSong(long songId) {
        return songId > 0L && userCloudSongIds.contains(songId);
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

    /**
     * Adjusts volume by a normalized delta and reports the resulting percentage.
     */
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
     *
     * @param songs    歌曲列表
     * @param startIdx 第一首播放的索引
     */
    @SneakyThrows
    public static void play(List<Music> songs, int startIdx) {
        exitPersonalFmSession();
        startPlaybackList(songs, startIdx);
    }

    /**
     * Starts a small personal FM batch without applying ordinary playlist modes.
     */
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
            String reportedType = AudioContainerSupport.normalizeReportedContainer(playUrl.getB());
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
                        AudioContainerSupport.normalizeReportedContainer(standardPlayUrl.getB()), song);
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
            File cachedMusic = AudioCacheFiles.findCachedAudioFile(musicCacheDir, cacheKey, type);
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

            String actualType = AudioContainerSupport.detectContainer(temporaryMusic);
            if (!AudioContainerSupport.isSupportedContainer(actualType)) {
                temporaryMusic.delete();
                throw new IllegalStateException("Downloaded audio has an unsupported or invalid container: " + cacheKey);
            }

            File music = new File(musicCacheDir, cacheKey + "." + actualType);
            if (music.exists()) {
                String existingType = AudioContainerSupport.detectContainer(music);
                if (actualType.equals(existingType)) {
                    temporaryMusic.delete();
                } else {
                    music.delete();
                    AudioCacheFiles.moveCacheFile(temporaryMusic, music);
                }
            } else {
                AudioCacheFiles.moveCacheFile(temporaryMusic, music);
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
            String container = AudioContainerSupport.detectContainer(sourceFile);
            if (!AudioContainerSupport.isSupportedContainer(container)) {
                throw new IllegalStateException("Cached audio has an unsupported or invalid container: "
                        + sourceFile.getName());
            }
            if (AudioContainerSupport.isMp4Container(container)) {
                discardLegacyDecodedWav(sourceFile);
                DownloadDynamicIsland.showUnsupportedMp4Container(sourceFile.getName());
                throw new UnsupportedMp4ContainerException(sourceFile.getName());
            }
            if (!AudioContainerSupport.requiresAacDecode(container)) {
                return sourceFile;
            }

            File decodedFile = AudioCacheFiles.getDecodedWavFile(sourceFile);
            if (AudioCacheFiles.isReusableDecodedWav(sourceFile, decodedFile)) {
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
                if (!AudioCacheFiles.isReusableDecodedWav(sourceFile, decodedFile)) {
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
            File decodedFile = AudioCacheFiles.getDecodedWavFile(sourceFile);
            if (decodedFile.exists() && !decodedFile.delete()) {
                System.err.println("[NCM] Unable to remove legacy MP4 decoded cache: " + decodedFile.getName());
            }
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
        MusicCoverService.loadMusicCover(music, forceReload);
    }

    public static BufferedImage gaussianBlur(BufferedImage imgIn, int blur) {
        return MusicCoverService.gaussianBlur(imgIn, blur);
    }

    private static final class UnsupportedMp4ContainerException extends IllegalStateException {
        private UnsupportedMp4ContainerException(String sourceName) {
            super("Detected unsupported MP4 container from file header: " + sourceName);
        }
    }

    private static void downloadMusic(String playUrl, File music) {
        MusicDownloadService.downloadMusic(playUrl, music);
    }

    public static void writeTo(InputStream src, OutputStream dest) {
        MusicDownloadService.writeTo(src, dest);
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
            LyricTimelineSupport.PreparedTimeline timeline = LyricTimelineSupport.prepare(
                    parsedLyrics == null ? Collections.<LyricLine>emptyList() : parsedLyrics);
            lyrics.clear();
            lyrics.addAll(timeline.lines);
            currentLyric = lyrics.get(0);
            haveNoWords = timeline.haveNoWords;
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
     * 更新当前歌词行
     *
     * @param songProgress 歌曲进度 (ms)
     */
    public static void updateCurrentLyric(float songProgress) {
        LyricLine previousLyric = currentLyric;
        currentLyric = findCurrentLyric(songProgress);

        if (previousLyric != currentLyric) {
            resetLyricPositionUpdate();
        }
    }

    public static LyricLine findCurrentLyric(double songProgress) {
        return LyricTimelineSupport.findCurrentLyric(lyrics, haveNoWords, songProgress);
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
        final File embeddedLyricFile = music.isCloudSong()
                ? LyricLoadService.resolveEmbeddedLyricFile(playbackFile) : null;

        MultiThreadingUtil.runAsync(() -> {
            User currentProfile = profile;
            long profileId = currentProfile == null ? 0L : currentProfile.getId();
            LyricLoadService.LyricLoadResult result = LyricLoadService.load(
                    music, songId, profileId, embeddedLyricFile);

            Music current = currentlyPlaying;
            if (!session.isActive() || current == null || !trackKey.equals(current.getStableKey())
                    || !trackKey.equals(session.trackKey)) {
                return;
            }

            final JsonObject committedJson = result.rawJson;
            final List<LyricLine> committedLyrics = result.lines == null
                    ? Collections.<LyricLine>emptyList() : result.lines;
            session.pendingLyrics = committedLyrics;
            MultiThreadingUtil.runOnMainThread(() -> applyLyricTimeline(session, committedJson, committedLyrics));
        });
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
        return NeteaseAccountRepository.getUserProfile();
    }

    public static List<Music> search(String keyWord) {
        List<Music> cadenceResults = CadenceMusicService.search(keyWord, 50);
        if (!cadenceResults.isEmpty() || CadenceMusicService.getCurrentPlatform() == MusicPlatform.QQ) {
            return cadenceResults;
        }

        // Cadence 网络失败时仅对网易云保留原 API 兜底，QQ 不可误发到网易云搜索。
        return NeteaseAccountRepository.searchSongs(keyWord);
    }

    public static List<Long> likeList() {
        return NeteaseAccountRepository.loadLikeList(profile);
    }

    public static String qrKey() {
        return NeteaseAccountRepository.qrKey();
    }

}
