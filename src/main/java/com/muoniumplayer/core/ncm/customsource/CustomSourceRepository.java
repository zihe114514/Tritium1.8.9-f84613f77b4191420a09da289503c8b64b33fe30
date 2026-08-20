package com.muoniumplayer.core.ncm.customsource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Keeps scripts as separate .js files and stores only their index/metadata in JSON. */
final class CustomSourceRepository {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CustomSourceRepository() {
    }

    static List<CustomSourceInfo> load() {
        File file = ConfigPaths.CUSTOM_SOURCES;
        if (!file.isFile()) return new ArrayList<>();
        try {
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            JsonArray entries = root == null ? null : root.getAsJsonArray("sources");
            List<CustomSourceInfo> result = new ArrayList<>();
            if (entries == null) return result;
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) continue;
                try {
                    CustomSourceInfo info = GSON.fromJson(element, CustomSourceInfo.class);
                    if (info != null && info.id != null && !info.id.trim().isEmpty()) result.add(info);
                } catch (Throwable ignored) {
                    // One damaged entry must not hide every other imported source.
                }
            }
            return result;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    static void save(List<CustomSourceInfo> sources) {
        try {
            File parent = ConfigPaths.CUSTOM_SOURCES.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JsonObject root = new JsonObject();
            JsonArray entries = new JsonArray();
            if (sources != null) {
                for (CustomSourceInfo source : sources) {
                    if (source != null) entries.add(GSON.toJsonTree(source));
                }
            }
            root.add("sources", entries);
            JsonConfigStorage.writeObject(ConfigPaths.CUSTOM_SOURCES, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}