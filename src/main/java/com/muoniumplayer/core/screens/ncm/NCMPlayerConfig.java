package com.muoniumplayer.core.screens.ncm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;


/** 持久化播放器窗口尺寸。布局使用真实逻辑尺寸，避免额外 GL 缩放造成点击和裁剪错位。 */
public final class NCMPlayerConfig {

    public static final float MIN_SCALE = 0.70f;
    public static final float MAX_SCALE = 1.00f;
    public static final float SCALE_STEP = 0.05f;

    private static final File FILE = ConfigPaths.PLAYER;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static float playerScale = 1.0f;
    private static Quality audioQuality = Quality.LOSSLESS;
    /**
     * Opt-in only. When enabled, MuoniumPlayer submits a NetEase listening record
     * only after the local audio state has actually played for the required time.
     */
    private static boolean neteaseListeningHistorySyncEnabled;
    private static boolean loaded;

    private NCMPlayerConfig() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        if (!FILE.exists()) return;
        try {
            JsonObject object = JsonConfigStorage.readObject(FILE, GSON);
            if (object != null && object.has("playerScale")) {
                playerScale = normalize(object.get("playerScale").getAsFloat());
            }
            if (object != null && object.has("audioQuality")) {
                try {
                    audioQuality = Quality.valueOf(object.get("audioQuality").getAsString());
                } catch (Throwable ignored) {
                    audioQuality = Quality.LOSSLESS;
                }
            }
            if (object != null && object.has("neteaseListeningHistorySyncEnabled")) {
                neteaseListeningHistorySyncEnabled = object.get("neteaseListeningHistorySyncEnabled").getAsBoolean();
            }
        } catch (Throwable ignored) {
            playerScale = 1.0f;
        }
    }

    public static synchronized float getPlayerScale() {
        load();
        return playerScale;
    }

    public static synchronized int getPlayerScalePercent() {
        return Math.round(getPlayerScale() * 100.0f);
    }

    public static synchronized Quality getAudioQuality() {
        load();
        return audioQuality == null ? Quality.LOSSLESS : audioQuality;
    }

    public static synchronized void setAudioQuality(Quality quality) {
        load();
        audioQuality = quality == null ? Quality.LOSSLESS : quality;
        save();
    }

    /** Returns whether real local NetEase playback sessions may be synchronized. */
    public static synchronized boolean isNeteaseListeningHistorySyncEnabled() {
        load();
        return neteaseListeningHistorySyncEnabled;
    }

    /** Persists the user's explicit consent for real-play listening-history synchronization. */
    public static synchronized void setNeteaseListeningHistorySyncEnabled(boolean enabled) {
        load();
        neteaseListeningHistorySyncEnabled = enabled;
        save();
    }

    public static synchronized float increaseScale() {
        load();
        return setPlayerScale(playerScale + SCALE_STEP);
    }

    public static synchronized float decreaseScale() {
        load();
        return setPlayerScale(playerScale - SCALE_STEP);
    }

    /** 单按钮严格按 100% → 90% → 80% → 70% → 100% 循环切换。 */
    public static synchronized float cycleScale() {
        load();

        // 不直接比较 float。旧 normalize() 会产生 0.90000004，导致 90% 每次都再次命中
        // “大于 90%”分支，按钮因此永远无法进入 80% 和 70%。
        int percent = Math.round(playerScale * 100.0f);
        if (percent >= 95) return setPlayerScale(.90f);
        if (percent >= 85) return setPlayerScale(.80f);
        if (percent >= 75) return setPlayerScale(.70f);
        return setPlayerScale(1.0f);
    }

    public static synchronized float resetScale() {
        return setPlayerScale(1.0f);
    }
    public static synchronized float setPlayerScale(float scale) {
        load();
        playerScale = normalize(scale);
        save();
        return playerScale;
    }

    private static float normalize(float scale) {
        float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        int steppedPercent = Math.round(clamped * 100.0f / 5.0f) * 5;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, steppedPercent / 100.0f));
    }

    private static void save() {
        try {
            JsonObject object = new JsonObject();
            object.addProperty("playerScale", playerScale);
            object.addProperty("audioQuality", getAudioQuality().name());
            object.addProperty("neteaseListeningHistorySyncEnabled", neteaseListeningHistorySyncEnabled);
            JsonConfigStorage.writeObject(FILE, GSON, object);

        } catch (Throwable ignored) {
        }
    }
}
