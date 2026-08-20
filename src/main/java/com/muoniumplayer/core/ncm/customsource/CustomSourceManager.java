package com.muoniumplayer.core.ncm.customsource;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Registry for imported LX-compatible scripts. Scripts are optional URL resolvers only: they never
 * replace the existing NetEase/QQ browsing, account or playlist implementations.
 */
public final class CustomSourceManager {

    public static final int MAX_SOURCES = 20;
    public static final int MAX_SCRIPT_BYTES = 9 * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static final List<CustomSourceInfo> SOURCES = new ArrayList<>();
    private static final Map<String, LxScriptRuntime> RUNTIMES = new HashMap<>();
    private static volatile boolean loaded;

    private CustomSourceManager() {
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            SOURCES.clear();
            SOURCES.addAll(CustomSourceRepository.load());
            sortLocked();
        }
        for (CustomSourceInfo source : getSources()) {
            if (source.enabled) initializeAsync(source.id);
        }
    }

    public static List<CustomSourceInfo> getSources() {
        load();
        synchronized (LOCK) {
            List<CustomSourceInfo> copy = new ArrayList<>();
            for (CustomSourceInfo source : SOURCES) copy.add(copyOf(source));
            return copy;
        }
    }

    public static boolean hasEnabledSources() {
        for (CustomSourceInfo source : getSources()) {
            if (source.enabled) return true;
        }
        return false;
    }

    /** Returns the one source selected by the user, or {@code null} when custom playback is off. */
    public static CustomSourceInfo getSelectedSource() {
        load();
        synchronized (LOCK) {
            for (CustomSourceInfo source : SOURCES) {
                if (source.enabled && source.selected && supports(source, source.selectedPlatform)) return copyOf(source);
            }
            return null;
        }
    }

    /** Returns the manually selected platform key for the current custom source, or an empty string. */
    public static String getSelectedPlatform() {
        CustomSourceInfo source = getSelectedSource();
        return source == null ? "" : safe(source.selectedPlatform).toLowerCase(Locale.ROOT);
    }

    /** Selects a source using its first declared platform. Kept for simple toggle interactions. */
    public static boolean select(String id) {
        load();
        String selectedId = safe(id);
        if (selectedId.isEmpty()) return select(null, null);
        synchronized (LOCK) {
            CustomSourceInfo target = findLocked(selectedId);
            if (target == null || !target.enabled || target.getDeclaredSources().isEmpty()) return false;
            return selectLocked(target, target.getDeclaredSources().get(0));
        }
    }

    /**
     * Explicitly selects one imported script and one platform declared by that script. No provider
     * is picked automatically: the stored key is used as the user's manual custom-source choice.
     */
    public static boolean select(String id, String platform) {
        load();
        String selectedId = safe(id);
        if (selectedId.isEmpty()) {
            synchronized (LOCK) {
                boolean changed = false;
                for (CustomSourceInfo source : SOURCES) {
                    if (source.selected || !safe(source.selectedPlatform).isEmpty()) {
                        source.selected = false;
                        source.selectedPlatform = "";
                        source.updatedAt = System.currentTimeMillis();
                        changed = true;
                    }
                }
                if (changed) saveLocked();
                return true;
            }
        }
        synchronized (LOCK) {
            CustomSourceInfo target = findLocked(selectedId);
            if (target == null || !target.enabled) return false;
            String key = safe(platform).toLowerCase(Locale.ROOT);
            if (!supports(target, key)) return false;
            return selectLocked(target, key);
        }
    }

    private static boolean selectLocked(CustomSourceInfo target, String platform) {
        boolean changed = false;
        for (CustomSourceInfo source : SOURCES) {
            boolean next = source.id.equals(target.id);
            String nextPlatform = next ? safe(platform).toLowerCase(Locale.ROOT) : "";
            if (source.selected != next || !nextPlatform.equals(safe(source.selectedPlatform).toLowerCase(Locale.ROOT))) {
                source.selected = next;
                source.selectedPlatform = nextPlatform;
                source.updatedAt = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) saveLocked();
        return true;
    }
    public static ImportResult importLocal(String pathText) {
        if (pathText == null || pathText.trim().isEmpty()) return ImportResult.failure("请选择本地 .js 音源文件");
        try {
            File file = new File(pathText.trim());
            if (!file.isFile()) return ImportResult.failure("本地音源文件不存在");
            if (file.length() > MAX_SCRIPT_BYTES) return ImportResult.failure("音源脚本超过 9 MB 限制");
            byte[] bytes = readLimited(new FileInputStream(file), MAX_SCRIPT_BYTES);
            return importScript(new String(bytes, StandardCharsets.UTF_8), "file:" + file.getAbsolutePath());
        } catch (Throwable throwable) {
            return ImportResult.failure(messageOf(throwable, "读取本地音源失败"));
        }
    }

    public static ImportResult importFromUrl(String address) {
        if (address == null || address.trim().isEmpty()) return ImportResult.failure("请输入音源脚本链接");
        String requested = address.trim();
        if (!requested.startsWith("http://") && !requested.startsWith("https://")) {
            return ImportResult.failure("仅支持 HTTP 或 HTTPS 音源链接");
        }
        try {
            byte[] bytes = downloadScript(requested);
            return importScript(new String(bytes, StandardCharsets.UTF_8), requested);
        } catch (Throwable throwable) {
            return ImportResult.failure(messageOf(throwable, "下载网络音源失败"));
        }
    }

    public static ImportResult importScript(String script, String origin) {
        if (script == null || script.trim().isEmpty()) return ImportResult.failure("音源脚本为空");
        if (script.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES) {
            return ImportResult.failure("音源脚本超过 9 MB 限制");
        }
        try {
            CustomSourceInfo info = new CustomSourceInfo();
            CustomSourceMetadataParser.apply(script, info);
            synchronized (LOCK) {
                load();
                if (SOURCES.size() >= MAX_SOURCES) return ImportResult.failure("最多可导入 " + MAX_SOURCES + " 个音源");
                for (CustomSourceInfo existing : SOURCES) {
                    String previous = readScriptLocked(existing);
                    if (script.equals(previous)) return ImportResult.failure("此音源脚本已导入：" + existing.name);
                }

                info.id = "lx_" + UUID.randomUUID().toString().replace("-", "");
                info.origin = safe(origin);
                info.scriptFile = info.id + ".js";
                info.importedAt = System.currentTimeMillis();
                info.updatedAt = info.importedAt;
                info.priority = SOURCES.size();
                info.runtimeStatus = "等待初始化";
                writeScriptLocked(info, script);
                SOURCES.add(info);
                sortLocked();
                saveLocked();
            }
            initializeAsync(info.id);
            DownloadDynamicIsland.showCustomSourceImportSuccess(info.name);
            return ImportResult.success(copyOf(info));
        } catch (Throwable throwable) {
            String message = messageOf(throwable, "导入音源失败");
            DownloadDynamicIsland.showCustomSourceFailure("导入失败", message);
            return ImportResult.failure(message);
        }
    }

    public static boolean setEnabled(String id, boolean enabled) {
        load();
        CustomSourceInfo target;
        synchronized (LOCK) {
            target = findLocked(id);
            if (target == null) return false;
            target.enabled = enabled;
            if (!enabled) {
                target.selected = false;
                target.selectedPlatform = "";
            }
            target.updatedAt = System.currentTimeMillis();
            target.runtimeStatus = enabled ? "等待初始化" : "已禁用";
            target.runtimeMessage = "";
            if (!enabled) closeRuntimeLocked(target.id);
            saveLocked();
        }
        if (enabled) initializeAsync(id);
        return true;
    }

    public static boolean remove(String id) {
        load();
        synchronized (LOCK) {
            CustomSourceInfo target = findLocked(id);
            if (target == null) return false;
            closeRuntimeLocked(target.id);
            SOURCES.remove(target);
            File script = scriptFile(target);
            if (script.isFile()) script.delete();
            sortLocked();
            saveLocked();
            return true;
        }
    }

    public static boolean movePriority(String id, int direction) {
        load();
        synchronized (LOCK) {
            sortLocked();
            int index = -1;
            for (int i = 0; i < SOURCES.size(); i++) if (safe(id).equals(SOURCES.get(i).id)) { index = i; break; }
            int target = index + (direction < 0 ? -1 : 1);
            if (index < 0 || target < 0 || target >= SOURCES.size()) return false;
            Collections.swap(SOURCES, index, target);
            for (int i = 0; i < SOURCES.size(); i++) SOURCES.get(i).priority = i;
            saveLocked();
            return true;
        }
    }

    /**
     * Called after official provider resolution fails. The script and target platform are both
     * explicit user choices; matching runs only for that target and never tries another platform.
     */
    public static Tuple<String, String> resolvePlaybackUrl(Music music, Quality requestedQuality) {
        if (music == null) return null;
        CustomSourceInfo source = getSelectedSource();
        String sourceKey = getSelectedPlatform();
        if (source == null || sourceKey.isEmpty()) return null;

        LxScriptRuntime runtime;
        synchronized (LOCK) { runtime = RUNTIMES.get(source.id); }
        if (runtime == null || !runtime.isReady()) {
            System.out.println("[LX Source] Selected source " + source.name + " is not ready.");
            return null;
        }

        try {
            String nativeKey = music.getSource() == MusicPlatform.QQ ? "tx" : "wy";
            Map<String, Object> musicInfo = nativeKey.equals(sourceKey)
                    ? buildMusicInfo(music) : LxManualSourceMatcher.find(music, sourceKey);
            if (musicInfo == null) throw new IllegalStateException("未找到与手动选择平台匹配的歌曲");
            DownloadDynamicIsland.showCustomSourceResolving(source.name + " · " + sourceKey.toUpperCase(Locale.ROOT));
            String url = runtime.resolveMusicUrl(sourceKey, musicInfo, qualityToLx(requestedQuality));
            if (!isHttpUrl(url)) throw new IllegalStateException("音源返回了无效 URL");
            String format = inferFormat(url);
            DownloadDynamicIsland.showCustomSourceResolved(source.name, format);
            return new Tuple<>(url, format);
        } catch (Throwable throwable) {
            String message = messageOf(throwable, "解析失败");
            System.err.println("[LX Source] Selected source " + source.name + " / " + sourceKey + " failed for "
                    + music.getName() + ": " + message);
            DownloadDynamicIsland.showCustomSourceFailure(source.name, message);
            return null;
        }
    }
    private static void initializeAsync(final String id) {
        MultiThreadingUtil.runAsync(() -> {
            CustomSourceInfo source;
            String script;
            synchronized (LOCK) {
                source = findLocked(id);
                if (source == null || !source.enabled) return;
                script = readScriptLocked(source);
                closeRuntimeLocked(id);
                source.runtimeStatus = "初始化中";
                source.runtimeMessage = "";
            }
            try {
                LxScriptRuntime runtime = new LxScriptRuntime(copyOf(source), script);
                List<String> declared = runtime.initialize();
                synchronized (LOCK) {
                    CustomSourceInfo live = findLocked(id);
                    if (live == null || !live.enabled) {
                        runtime.close();
                        return;
                    }
                    live.declaredSources = new ArrayList<>(declared);
                    if (live.selected && !supports(live, live.selectedPlatform)) {
                        live.selected = false;
                        live.selectedPlatform = "";
                    }
                    live.runtimeStatus = "已就绪";
                    live.runtimeMessage = "";
                    RUNTIMES.put(id, runtime);
                    saveLocked();
                }
                DownloadDynamicIsland.showCustomSourceReady(source.name);
                System.out.println("[LX Source] Ready: " + source.name + " -> " + declared);
            } catch (Throwable throwable) {
                markRuntimeFailure(id, messageOf(throwable, "初始化失败"), true);
            }
        });
    }

    private static void markRuntimeFailure(String id, String message, boolean notify) {
        synchronized (LOCK) {
            CustomSourceInfo source = findLocked(id);
            if (source == null) return;
            closeRuntimeLocked(id);
            source.runtimeStatus = "不可用";
            source.runtimeMessage = safe(message);
            saveLocked();
            if (notify) DownloadDynamicIsland.showCustomSourceFailure(source.name, source.runtimeMessage);
        }
    }

    private static Map<String, Object> buildMusicInfo(Music music) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", music.getId());
        info.put("songmid", music.getSourceId());
        info.put("name", safe(music.getName()));
        info.put("title", safe(music.getName()));
        info.put("singer", safe(music.getArtistsName()));
        info.put("artists", safe(music.getArtistsName()));
        info.put("albumName", music.getAlbum() == null ? "" : safe(music.getAlbum().getName()));
        info.put("albumId", music.getAlbum() == null ? 0L : music.getAlbum().getId());
        info.put("duration", music.getDuration());
        info.put("interval", formatDuration(music.getDuration()));
        info.put("source", music.getSource().name().toLowerCase(Locale.ROOT));
        // QQ's sourceMid is exposed by Lombok; JS sources commonly call it strMediaMid.
        try {
            Object sourceMid = music.getClass().getMethod("getSourceMid").invoke(music);
            info.put("strMediaMid", sourceMid == null ? "" : String.valueOf(sourceMid));
        } catch (Throwable ignored) {
            info.put("strMediaMid", "");
        }
        return info;
    }

    private static String qualityToLx(Quality quality) {
        if (quality == Quality.LOSSLESS || quality == Quality.HIRES || quality == Quality.JYEFFECT || quality == Quality.JYMASTER) return "flac";
        if (quality == Quality.HIGHER || quality == Quality.EXHIGH || quality == Quality.SKY) return "320k";
        return "128k";
    }

    private static boolean supports(CustomSourceInfo source, String key) {
        for (String value : source.getDeclaredSources()) if (key.equalsIgnoreCase(value)) return true;
        return false;
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private static String inferFormat(String url) {
        String value = safe(url).toLowerCase(Locale.ROOT);
        int query = value.indexOf('?'); if (query >= 0) value = value.substring(0, query);
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) return value.substring(dot + 1);
        return "未知";
    }

    private static boolean isHttpUrl(String url) {
        return url != null && url.length() <= 2048 && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static byte[] downloadScript(String address) throws IOException {
        URL url = new URL(address);
        for (int redirect = 0; redirect <= 3; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(18_000);
            connection.setRequestProperty("User-Agent", "MuoniumPlayer CustomSource Import/1.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) throw new IOException("音源链接重定向无目标");
                url = new URL(url, location);
                if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
                    throw new IOException("重定向协议不受支持");
                }
                continue;
            }
            if (status < 200 || status >= 300) throw new IOException("下载请求返回 HTTP " + status);
            try (InputStream input = connection.getInputStream()) { return readLimited(input, MAX_SCRIPT_BYTES); }
            finally { connection.disconnect(); }
        }
        throw new IOException("音源链接重定向次数过多");
    }

    private static byte[] readLimited(InputStream input, int max) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > max) throw new IOException("音源脚本超过 9 MB 限制");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeScriptLocked(CustomSourceInfo info, String script) throws IOException {
        File directory = ConfigPaths.CUSTOM_SOURCE_DIRECTORY;
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法创建音源目录");
        File target = scriptFile(info);
        File temp = new File(directory, info.scriptFile + ".tmp");
        Files.write(temp.toPath(), script.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException noAtomicMove) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String readScriptLocked(CustomSourceInfo info) {
        try {
            File script = scriptFile(info);
            if (!script.isFile() || script.length() > MAX_SCRIPT_BYTES) return "";
            return new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static File scriptFile(CustomSourceInfo info) {
        String fileName = info == null || info.scriptFile == null || info.scriptFile.trim().isEmpty()
                ? "missing.js" : new File(info.scriptFile).getName();
        return new File(ConfigPaths.CUSTOM_SOURCE_DIRECTORY, fileName);
    }

    private static void closeRuntimeLocked(String id) {
        LxScriptRuntime runtime = RUNTIMES.remove(id);
        if (runtime != null) runtime.close();
    }

    private static CustomSourceInfo findLocked(String id) {
        for (CustomSourceInfo source : SOURCES) if (source.id != null && source.id.equals(id)) return source;
        return null;
    }

    private static void saveLocked() { CustomSourceRepository.save(SOURCES); }

    private static void sortLocked() {
        Collections.sort(SOURCES, new Comparator<CustomSourceInfo>() {
            @Override public int compare(CustomSourceInfo a, CustomSourceInfo b) { return Integer.compare(a.priority, b.priority); }
        });
        for (int i = 0; i < SOURCES.size(); i++) SOURCES.get(i).priority = i;
    }

    private static CustomSourceInfo copyOf(CustomSourceInfo source) {
        CustomSourceInfo copy = new CustomSourceInfo();
        copy.id = safe(source.id); copy.name = safe(source.name); copy.description = safe(source.description);
        copy.version = safe(source.version); copy.author = safe(source.author); copy.homepage = safe(source.homepage);
        copy.origin = safe(source.origin); copy.scriptFile = safe(source.scriptFile); copy.enabled = source.enabled;
        copy.selected = source.selected; copy.selectedPlatform = safe(source.selectedPlatform); copy.priority = source.priority; copy.importedAt = source.importedAt; copy.updatedAt = source.updatedAt;
        copy.declaredSources = new ArrayList<>(source.getDeclaredSources()); copy.runtimeStatus = safe(source.runtimeStatus);
        copy.runtimeMessage = safe(source.runtimeMessage); return copy;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String messageOf(Throwable error, String fallback) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? fallback : message.trim();
    }

    public static final class ImportResult {
        public final boolean success; public final String message; public final CustomSourceInfo source;
        private ImportResult(boolean success, String message, CustomSourceInfo source) { this.success = success; this.message = message; this.source = source; }
        static ImportResult success(CustomSourceInfo source) { return new ImportResult(true, "导入成功", source); }
        static ImportResult failure(String message) { return new ImportResult(false, safe(message), null); }
    }
}