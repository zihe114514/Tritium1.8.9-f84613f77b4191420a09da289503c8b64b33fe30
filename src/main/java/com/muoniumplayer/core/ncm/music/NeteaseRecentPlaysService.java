package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.json.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网易云"最近播放"的读取与队列拼装。
 *
 * <p>这段解析原来只长在 {@code NeteaseDiscoveryPanel} 的私有方法里，只有那个发现页能用。搜索结果
 * 起播现在也要用同一份数据，所以抽到播放层：界面与播放链路读的是同一个解析实现，两边不会因为各写
 * 一份而慢慢分叉。行为与原实现完全一致（同样的递归下探、同样的 100 条上限、同样的
 * {@code applyNeteaseMetadata}）。</p>
 *
 * <p>接口响应结构在网易云这边多次变过（{@code data} / {@code list} / {@code allData}，每项可能是
 * {@code {song:{...}}} 也可能就是歌曲对象本身），所以这里不写死路径，而是深度优先地找出第一层看起来
 * 像歌曲的对象。找不到就返回空列表——调用方一律把"空"当作"没有最近播放"来兜底，不当成错误。</p>
 */
public final class NeteaseRecentPlaysService {

    /** 接口自身的上限，也是这里的解析上限。 */
    public static final int MAX_RECENT_SONGS = 100;

    private NeteaseRecentPlaysService() {
    }

    /**
     * 拉取一次最近播放。网络失败、未登录、Cookie 失效都返回空列表而不是抛异常：调用方全部走兜底。
     *
     * <p>必须在后台线程调用。</p>
     */
    public static List<Music> fetchRecentSongs(int limit) {
        try {
            JsonObject root = CloudMusicApi.recentSongs(clampLimit(limit)).toJsonObject();
            return parseRecentSongs(root, clampLimit(limit));
        } catch (Throwable failure) {
            System.err.println("[NCM] 最近播放拉取失败: " + failure.getMessage());
            return Collections.emptyList();
        }
    }

    /** 解析 {@code /api/play-record/song/list} 的响应。纯函数，可离线单测。 */
    public static List<Music> parseRecentSongs(JsonObject root, int limit) {
        List<Music> result = new ArrayList<>();
        collect(root, result, clampLimit(limit));
        return result;
    }

    /**
     * 用最近播放拼出一条播放队列：用户点的那一首永远排第一，后面接最近播放里剩下的曲目。
     *
     * <p>去重按 {@link Music#equals(Object)}（来源 + 来源内 id），所以同一首歌不会因为最近播放里
     * 出现过而在队列里排两次。{@code selected} 为空时直接返回空列表，调用方据此回退。</p>
     */
    public static List<Music> buildQueue(Music selected, List<Music> recent) {
        if (selected == null) return Collections.emptyList();

        List<Music> queue = new ArrayList<>();
        Set<Music> seen = new HashSet<>();
        queue.add(selected);
        seen.add(selected);
        if (recent == null) return queue;

        for (Music song : recent) {
            if (song == null || !seen.add(song)) continue;
            queue.add(song);
        }
        return queue;
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return MAX_RECENT_SONGS;
        return Math.min(MAX_RECENT_SONGS, limit);
    }

    private static void collect(JsonElement element, List<Music> result, int limit) {
        if (element == null || element.isJsonNull() || result.size() >= limit) return;
        try {
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                JsonObject song = childObject(object, "song");
                if (song != null && longValue(song, "id") > 0L) {
                    addSong(song, result);
                    return;
                }
                if (longValue(object, "id") > 0L && object.has("name")) {
                    addSong(object, result);
                    return;
                }
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    collect(entry.getValue(), result, limit);
                    if (result.size() >= limit) return;
                }
            } else if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    collect(child, result, limit);
                    if (result.size() >= limit) return;
                }
            }
        } catch (Throwable ignored) {
            // 单个节点结构不对不应该让整份响应作废。
        }
    }

    private static void addSong(JsonObject song, List<Music> result) {
        try {
            Music music = JsonUtils.parse(song, Music.class);
            if (music == null) return;
            // 与歌单/搜索走同一条元数据补全路径，最高音质与 VIP 标记才不会缺。
            music.applyNeteaseMetadata(song, null);
            result.add(music);
        } catch (Throwable ignored) {
        }
    }

    private static JsonObject childObject(JsonObject object, String name) {
        if (object == null || name == null || !object.has(name) || !object.get(name).isJsonObject()) return null;
        return object.getAsJsonObject(name);
    }

    private static long longValue(JsonObject object, String name) {
        if (object == null || name == null) return 0L;
        try {
            if (object.has(name) && !object.get(name).isJsonNull()) {
                return object.get(name).getAsLong();
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
