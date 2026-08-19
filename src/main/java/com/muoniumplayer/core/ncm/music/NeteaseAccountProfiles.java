package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local, cookie-based NetEase account switcher. Cookies never leave the existing NetEase request path. */
public final class NeteaseAccountProfiles {

    private static final File FILE = new File("config/tritium/netease_accounts.json");
    private static final Gson GSON = new Gson();

    private NeteaseAccountProfiles() { }

    public static synchronized List<Account> load() {
        if (!FILE.isFile()) return Collections.emptyList();
        try {
            JsonObject root = JsonConfigStorage.readObject(FILE, GSON);
            JsonArray accounts = root == null ? null : root.getAsJsonArray("accounts");
            if (accounts == null) return Collections.emptyList();
            List<Account> result = new ArrayList<Account>();
            for (JsonElement element : accounts) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String cookie = string(object, "cookie");
                if (cookie.isEmpty()) continue;
                result.add(new Account(string(object, "name"), string(object, "id"), cookie));
            }
            return result;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public static synchronized void saveCurrent() {
        String cookie = OptionsUtil.getCookie();
        if (cookie == null || cookie.trim().isEmpty() || CloudMusic.profile == null) return;
        String id = String.valueOf(CloudMusic.profile.getId());
        String name = CloudMusic.profile.getName() == null ? "网易云账号" : CloudMusic.profile.getName();
        List<Account> accounts = new ArrayList<Account>(load());
        for (int i = accounts.size() - 1; i >= 0; i--) {
            Account old = accounts.get(i);
            if (cookie.equals(old.cookie) || (!id.isEmpty() && id.equals(old.id))) accounts.remove(i);
        }
        accounts.add(0, new Account(name, id, cookie));
        while (accounts.size() > 8) accounts.remove(accounts.size() - 1);
        save(accounts);
    }

    public static synchronized boolean switchTo(Account account) {
        if (account == null || account.cookie.trim().isEmpty()) return false;
        CloudMusic.loadNCM(account.cookie);
        if (CloudMusic.profile == null) return false;
        saveCurrent();
        return true;
    }

    private static void save(List<Account> accounts) {
        try {
            File parent = FILE.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JsonObject root = new JsonObject();
            JsonArray array = new JsonArray();
            for (Account account : accounts) {
                JsonObject object = new JsonObject();
                object.addProperty("name", account.name);
                object.addProperty("id", account.id);
                object.addProperty("cookie", account.cookie);
                array.add(object);
            }
            root.add("accounts", array);
            JsonConfigStorage.writeObject(FILE, GSON, root);
        } catch (Throwable ignored) { }
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    public static final class Account {
        public final String name;
        public final String id;
        private final String cookie;
        private Account(String name, String id, String cookie) { this.name = name; this.id = id; this.cookie = cookie; }
        public String getDisplayName() { return name == null || name.trim().isEmpty() ? "网易云账号" : name; }
    }
}
