package tritium.ncm.music.dto;

import com.google.gson.JsonArray;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import tritium.ncm.RequestUtil;
import tritium.ncm.api.CloudMusicApi;
import tritium.utils.Location;
import tritium.utils.json.JsonUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 歌单对象
 */
@Data
public class PlayList {

    @SerializedName("id")
    private final long id;

    @SerializedName("name")
    private final String name;

    @SerializedName(value = "coverImgUrl")
    private final String coverUrl;

    @SerializedName("trackCount")
    private final int count;

    @SerializedName(value = "playCount")
    private final long playCount;

    @SerializedName("creator")
    private final User creator;

    @SerializedName("description")
    private final String description;

    @SerializedName("subscribed")
    private boolean subscribed;

    @SerializedName("createTime")
    private final long createTime;

    // unique fields
    public transient List<Music> musics;
    private transient boolean searchMode = false;
    public transient volatile boolean musicsQueried = false, musicsLoaded = false;

    public final Location getCoverLocation() {
        return Location.of("tritium/textures/playlist/" + this.id + "/cover.png");
    }

    public List<Music> getMusics() {
        boolean startQuery = false;
        List<Music> currentMusics;

        synchronized (this) {
            ensureMusicsList();
            currentMusics = this.musics;
            if (!this.musicsQueried && !searchMode) {
                this.musicsQueried = true;
                startQuery = true;
            }
        }

        if (startQuery) {
            startMusicQuery();
        }
        return currentMusics;
    }

    /**
     * Registers a listener for the current load, including a load that was already
     * started by {@link #getMusics()}. This prevents a panel from missing the
     * result when it opens while a large playlist is still downloading.
     */
    public void loadMusicsWithCallback(MusicsLoadedCallback callback) {
        if (callback == null) {
            return;
        }

        boolean startQuery = false;
        List<Music> alreadyLoaded = null;
        synchronized (this) {
            ensureMusicsList();
            if (this.musicsLoaded || (searchMode && !this.musics.isEmpty())) {
                alreadyLoaded = this.musics;
            } else {
                getMusicLoadCallbacks().add(callback);
                if (!this.musicsQueried && !searchMode) {
                    this.musicsQueried = true;
                    startQuery = true;
                }
            }
        }

        if (alreadyLoaded != null) {
            callback.onMusicsLoaded(alreadyLoaded);
        } else if (startQuery) {
            startMusicQuery();
        }
    }

    private transient List<MusicsLoadedCallback> musicLoadCallbacks;

    private void ensureMusicsList() {
        if (this.musics == null) {
            this.musics = new CopyOnWriteArrayList<>();
        }
    }

    private List<MusicsLoadedCallback> getMusicLoadCallbacks() {
        if (this.musicLoadCallbacks == null) {
            this.musicLoadCallbacks = new ArrayList<>();
        }
        return this.musicLoadCallbacks;
    }

    private void startMusicQuery() {
        MultiThreadingUtil.runAsync(() -> {
            queryMusics();
            notifyMusicLoadCallbacks();
        });
    }

    private void notifyMusicLoadCallbacks() {
        final List<MusicsLoadedCallback> callbacks;
        final List<Music> result;
        synchronized (this) {
            callbacks = new ArrayList<>(getMusicLoadCallbacks());
            getMusicLoadCallbacks().clear();
            result = this.musics;
        }

        if (callbacks.isEmpty()) {
            return;
        }

        // UI panels create widgets in these callbacks, so dispatch them on the
        // Minecraft main thread rather than the background HTTP worker.
        MultiThreadingUtil.runOnMainThread(() -> {
            for (MusicsLoadedCallback callback : callbacks) {
                try {
                    callback.onMusicsLoaded(result);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void queryMusics() {
        try {
            RequestUtil.RequestAnswer requestAnswer = CloudMusicApi.playlistTrackAll(id, 8);
            JsonArray songs = requestAnswer.toJsonObject().getAsJsonArray("songs");
            if (songs == null) {
                throw new IllegalStateException("playlist song detail response does not contain songs");
            }

            // CopyOnWriteArrayList copies its backing array on every single add.
            // Parse first, then publish the complete result in one addAll call.
            List<Music> loadedMusics = new ArrayList<>(songs.size());
            songs.forEach(element -> loadedMusics.add(JsonUtils.parse(element.getAsJsonObject(), Music.class)));

            synchronized (this) {
                ensureMusicsList();
                this.musics.clear();
                this.musics.addAll(loadedMusics);
                this.musicsLoaded = true;
            }
        } catch (Exception e) {
            synchronized (this) {
                this.musicsQueried = false;
                this.musicsLoaded = false;
            }
            e.printStackTrace();
        }
    }
    public interface MusicsLoadedCallback {
        void onMusicsLoaded(List<Music> musics);
    }

    public void updPlayCount() {
        CloudMusicApi.playlistUpdatePlaycount(this.id);
    }

    /**
     * 加入歌单并返回真实的服务端校验结果，供需要展示反馈的界面使用。
     */
    public CloudMusicApi.PlaylistTrackOperationResult addToListWithResult(long musicId) {
        return CloudMusicApi.addTrackToPlaylist(this.id, musicId);
    }

    /**
     * 兼容旧调用点：仍可直接提交加入请求，但不读取结果。
     */
    public void addToList(long musicId) {
        addToListWithResult(musicId);
    }

    /**
     * 收藏/取消收藏本歌单。本地状态由 Lombok 生成的 setSubscribed 维护，
     * 此方法仅负责调用原 API 链路（CloudMusicApi.subscribe → /weapi/playlist/subscribe|unsubscribe）。
     */
    public void subscribe(boolean subscribed) {
        CloudMusicApi.subscribe(this.id, subscribed);
    }

    public void removeFromList(long musicId) {
        CloudMusicApi.playlistTracks("del", this.id, String.valueOf(musicId));
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PlayList playList = (PlayList) o;
        return id == playList.id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
