package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.util.Locale;

/** Persists the one GD Studio API platform selected in the music-source menu. */
public final class GdStudioSourceSettings {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static volatile boolean loaded;
    private static String platform = "";

    private GdStudioSourceSettings() {
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            platform = loadPlatformLocked();
        }
    }

    public static String getPlatform() {
        load();
        synchronized (LOCK) {
            return platform;
        }
    }

    public static boolean isEnabled() {
        return !getPlatform().isEmpty();
    }

    /** Selects a known GD Studio platform. An empty value disables the source. */
    public static boolean setPlatform(String value) {
        String key = normalize(value);
        if (!key.isEmpty() && !GdStudioMusicService.isKnownPlatform(key)) return false;
        load();
        synchronized (LOCK) {
            platform = key;
            savePlatformLocked();
            return true;
        }
    }

    private static String loadPlatformLocked() {
        try {
            File file = ConfigPaths.GD_SOURCE;
            if (!file.isFile()) return "";
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null || !root.has("platform") || root.get("platform").isJsonNull()) return "";
            String key = normalize(root.get("platform").getAsString());
            return key.isEmpty() || GdStudioMusicService.isKnownPlatform(key) ? key : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void savePlatformLocked() {
        try {
            File parent = ConfigPaths.GD_SOURCE.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            if (!platform.isEmpty()) root.addProperty("platform", platform);
            JsonConfigStorage.writeObject(ConfigPaths.GD_SOURCE, GSON, root);
        } catch (Throwable ignored) {
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
