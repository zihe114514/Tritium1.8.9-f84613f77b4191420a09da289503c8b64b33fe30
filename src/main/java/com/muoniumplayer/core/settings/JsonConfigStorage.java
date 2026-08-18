package com.muoniumplayer.core.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Shared UTF-8 JSON object I/O for persisted player settings. */
public final class JsonConfigStorage {

    private JsonConfigStorage() {
    }

    public static JsonObject readObject(File file, Gson gson) throws IOException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        }
    }

    public static void writeObject(File file, Gson gson, JsonObject object) throws IOException {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(gson.toJson(object));
        }
    }
}
