package tritium.ncm.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import tritium.ncm.DeviceIdGenerator;
import tritium.ncm.OptionsUtil;
import tritium.ncm.RequestUtil;
import tritium.utils.json.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author IzumiiKonata
 * Date: 2025/7/2 19:56
 */
@UtilityClass
public class CloudMusicApi {

    public RequestUtil.RequestAnswer lyricNew(long id) {

        Map<String, Object> data = new HashMap<>();

        data.put("id", id);
        data.put("cp", false);
        data.put("tv", 0);
        data.put("lv", 0);
        data.put("rv", 0);
        data.put("kv", 0);
        data.put("yv", 0);
        data.put("ytv", 0);
        data.put("yrv", 0);

        return RequestUtil.createRequest("/api/song/lyric/v1", data, OptionsUtil.createOptions());
    }

    public RequestUtil.RequestAnswer loginStatus() {

        RequestUtil.RequestAnswer request = RequestUtil.createRequest("/api/w/nuser/account/get", new HashMap<>(), OptionsUtil.createOptions("weapi"));

        JsonObject result = request.toJsonObject();

        if (request.getStatus() == 200) {

            JsonObject objResult = new JsonObject();

            objResult.addProperty("status", 200);
            objResult.add("data", request.toJsonObject());
            if (request.getCookies() != null) {
                objResult.addProperty("cookie", String.join(";", request.getCookies()));
            }

            result = objResult;
        }

        return RequestUtil.RequestAnswer.of(result, 200, request.getCookies());
    }

    @SneakyThrows
    public RequestUtil.RequestAnswer cloudSearch(String keyWord, @NonNull SearchType type) {

        Map<String, Object> data = new HashMap<>();

        data.put("s", keyWord);
        data.put("type", type.getId());
        data.put("limit", 100);
        data.put("offset", 0);
        data.put("total", true);

        return RequestUtil.createRequest("/api/cloudsearch/pc", data, OptionsUtil.createOptions());

    }

    public enum SearchType {

        Single(1),
        Album(10),
        Singer(100),
        Playlist(1000),
        User(1002),
        MV(1004),
        Lyric(1006),
        Radio(1009),
        Video(1014),
        All(1018),
        Sound(2000);

        @Getter
        private final int id;

        SearchType(int id) {
            this.id = id;
        }

    }

    public RequestUtil.RequestAnswer likeList(long uid) {

        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);

        return RequestUtil.createRequest("/api/song/like/get", data, OptionsUtil.createOptions());

    }

    public RequestUtil.RequestAnswer loginQrKey() {

        Map<String, Object> data = new HashMap<>();
        data.put("type", 3);

        RequestUtil.RequestAnswer request = RequestUtil.createRequest("/api/login/qrcode/unikey", data, OptionsUtil.createOptions());

        JsonObject obj = new JsonObject();
        obj.addProperty("status", 200);
        obj.add("data", request.toJsonObject());
        if (request.getCookies() != null) {
            obj.addProperty("cookie", String.join(";", request.getCookies()));
        }

        return RequestUtil.RequestAnswer.of(obj, 200, request.getCookies());
    }

    public RequestUtil.RequestAnswer loginQrCheck(String key) {

        Map<String, Object> data = new HashMap<>();
        data.put("key", key);
        data.put("type", 3);

        RequestUtil.RequestAnswer request = RequestUtil.createRequest("/api/login/qrcode/client/login", data, OptionsUtil.createOptions());

//        JsonObject obj = new JsonObject();
//        obj.addProperty("status", 200);
//
//        JsonObject objBody = new JsonObject();
//
//        if (request.getStatus() == 200) {
//            JsonObject jsonObject = gson.fromJson(request.toJsonString(), JsonObject.class);
//            jsonObject.addProperty("cookie", String.join(";", request.getCookies()));
//            objBody.add("body", jsonObject);
//        }
//
//        obj.add("body", objBody);
//        obj.addProperty("cookie", String.join(";", request.getCookies()));
//
//        return RequestUtil.RequestAnswer.of(obj, 200, request.getCookies());

        if (request.getCookies() != null) {
            ((Map<String, Object>) request.getBody()).put("cookie", String.join(";", request.getCookies()));

        }

        return request;
    }

    public RequestUtil.RequestAnswer songUrlV1(long id, String level) {

        Map<String, Object> data = new HashMap<>();
        data.put("ids", "[" + id + "]");
        data.put("level", level);
        data.put("encodeType", "flac");

        if (level.equals("sky")) {
            data.put("immerseType", "c51");
        }

        return RequestUtil.createRequest("/api/song/enhance/player/url/v1", data, OptionsUtil.createOptions());
    }

    public RequestUtil.RequestAnswer like(long id, boolean like) {

        Map<String, Object> data = new HashMap<>();
        data.put("alg", "itembased");
        data.put("trackId", id);
        data.put("like", like);
        data.put("time", 3);

        return RequestUtil.createRequest("/api/radio/like", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * Loads all songs in a playlist without putting every track id into one request.
     *
     * <p>The v6 playlist response provides the ordered {@code trackIds} list, but
     * {@code /api/v3/song/detail} rejects or times out on a very large {@code c}
     * payload. Fetching details in bounded batches keeps large playlists reliable
     * while the final response restores the playlist's original song order.</p>
     */
    public RequestUtil.RequestAnswer playlistTrackAll(long id, int s) {
        final int detailBatchSize = 200;

        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("n", 100000);
        data.put("s", s);

        RequestUtil.RequestAnswer v6Detail = RequestUtil.createRequest(
                "/api/v6/playlist/detail", data, OptionsUtil.createOptions());
        JsonObject v6Obj = v6Detail.toJsonObject();
        JsonObject playlist = v6Obj.getAsJsonObject("playlist");
        if (playlist == null) {
            throw new IllegalStateException("playlist detail missing 'playlist' field (status="
                    + v6Detail.getStatus() + ")");
        }

        JsonArray trackIds = playlist.getAsJsonArray("trackIds");
        if (trackIds == null) {
            throw new IllegalStateException("playlist detail missing 'trackIds' field (status="
                    + v6Detail.getStatus() + ")");
        }

        List<Long> orderedIds = new ArrayList<>(trackIds.size());
        for (JsonElement trackId : trackIds) {
            if (trackId != null && trackId.isJsonObject()
                    && trackId.getAsJsonObject().has("id")) {
                orderedIds.add(trackId.getAsJsonObject().get("id").getAsLong());
            }
        }

        Map<Long, JsonObject> songsById = new HashMap<>();
        for (int start = 0; start < orderedIds.size(); start += detailBatchSize) {
            int end = Math.min(start + detailBatchSize, orderedIds.size());
            List<String> batch = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                batch.add("{\"id\":" + orderedIds.get(index) + "}");
            }

            Map<String, Object> detailData = new HashMap<>();
            detailData.put("c", "[" + String.join(",", batch) + "]");
            RequestUtil.RequestAnswer detailAnswer = RequestUtil.createRequest(
                    "/api/v3/song/detail", detailData, OptionsUtil.createOptions());
            JsonArray songs = detailAnswer.toJsonObject().getAsJsonArray("songs");
            if (songs == null) {
                throw new IllegalStateException("song detail batch missing 'songs' field (status="
                        + detailAnswer.getStatus() + ")");
            }

            for (JsonElement songElement : songs) {
                if (songElement != null && songElement.isJsonObject()) {
                    JsonObject song = songElement.getAsJsonObject();
                    if (song.has("id")) {
                        songsById.put(song.get("id").getAsLong(), song);
                    }
                }
            }
        }

        JsonArray orderedSongs = new JsonArray();
        for (Long songId : orderedIds) {
            JsonObject song = songsById.get(songId);
            if (song != null) {
                orderedSongs.add(song);
            }
        }

        JsonObject result = new JsonObject();
        result.add("songs", orderedSongs);
        return RequestUtil.RequestAnswer.of(result, v6Detail.getStatus(), v6Detail.getCookies());
    }
    public RequestUtil.RequestAnswer playlistUpdatePlaycount(long id) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);

        return RequestUtil.createRequest("/api/playlist/update/playcount", data, OptionsUtil.createOptions());
    }

    /**
     * 对歌单中的歌曲执行添加或删除操作。
     *
     * <p>返回真实的 HTTP 响应和服务端响应体，调用方必须同时校验 HTTP 状态与响应中的 {@code code}。
     * 这里不能再把失败包装成 200，否则 UI 会把权限、登录或网络错误错误地提示为成功。</p>
     *
     * @param operation {@code add} 或 {@code del}
     * @param trackId 歌单 Id
     * @param musics 用英文逗号分割的音乐 Id
     */
    public RequestUtil.RequestAnswer playlistTracks(String operation, long trackId, String musics) {
        String[] split = musics.split(",");
        Map<String, Object> data = new HashMap<>();
        data.put("op", operation);
        data.put("pid", trackId);
        data.put("trackIds", JsonUtils.toJsonString(split));
        data.put("imme", "true");

        // 歌单编辑接口使用 weapi 请求链路，与桌面客户端接口的会话校验保持一致。
        return RequestUtil.createRequest(
                "/api/playlist/manipulate/tracks",
                data,
                OptionsUtil.createOptions("weapi")
        );
    }

    /**
     * 将单曲加入指定歌单，并把 HTTP 层与网易云业务层的结果归一化给界面使用。
     */
    public PlaylistTrackOperationResult addTrackToPlaylist(long playlistId, long musicId) {
        RequestUtil.RequestAnswer request = playlistTracks("add", playlistId, String.valueOf(musicId));
        JsonObject body = null;
        int apiCode = -1;
        String message = "";

        try {
            body = request.toJsonObject();
            apiCode = getInt(body, "code", -1);
            message = getMessage(body);
        } catch (Exception ignored) {
            // 请求工具在遇到非 JSON 响应时也会保留 HTTP 状态；下面会给出可读的失败提示。
        }

        boolean httpSuccess = request.getStatus() >= 200 && request.getStatus() < 300;
        boolean apiSuccess = apiCode == 200;
        boolean alreadyExists = isAlreadyInPlaylist(message);
        boolean success = httpSuccess && apiSuccess;

        if (message.isEmpty()) {
            if (success) {
                message = "已加入歌单";
            } else if (request.getStatus() == 401) {
                message = "登录状态已失效，请重新登录";
            } else if (request.getStatus() == 403) {
                message = "没有操作此歌单的权限";
            } else if (!httpSuccess) {
                message = "网络请求失败（HTTP " + request.getStatus() + "）";
            } else if (apiCode != -1) {
                message = "网易云音乐返回错误（代码 " + apiCode + "）";
            } else {
                message = "服务端返回异常，请稍后重试";
            }
        }

        return new PlaylistTrackOperationResult(success, alreadyExists, request.getStatus(), apiCode, message);
    }

    private int getInt(JsonObject object, String key, int defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String getMessage(JsonObject object) {
        if (object == null) {
            return "";
        }
        for (String key : Arrays.asList("message", "msg")) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                continue;
            }
            try {
                String value = object.get(key).getAsString();
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private boolean isAlreadyInPlaylist(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("已存在")
                || text.contains("已经存在")
                || text.contains("已收藏")
                || text.contains("already exists")
                || text.contains("already in");
    }

    @Getter
    public static class PlaylistTrackOperationResult {
        private final boolean success;
        private final boolean alreadyExists;
        private final int httpStatus;
        private final int apiCode;
        private final String message;

        public PlaylistTrackOperationResult(boolean success, boolean alreadyExists, int httpStatus, int apiCode, String message) {
            this.success = success;
            this.alreadyExists = alreadyExists;
            this.httpStatus = httpStatus;
            this.apiCode = apiCode;
            this.message = message;
        }
    }

    public RequestUtil.RequestAnswer userPlaylist(long uid, int limit, int offset) {
        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);
        data.put("limit", limit);
        data.put("offset", offset);
        data.put("includeVideo", true);

        return RequestUtil.createRequest("/api/user/playlist", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 收藏/取消收藏歌单（原项目无此接口，需新增；复用 weapi 加密与 OptionsUtil 链路）。
     * 网易云歌单收藏接口：POST /weapi/playlist/subscribe 或 /weapi/playlist/unsubscribe，data={id}。
     * @param id  歌单 Id
     * @param sub true=收藏，false=取消收藏
     */
    public RequestUtil.RequestAnswer subscribe(long id, boolean sub) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);

        return RequestUtil.createRequest("/api/playlist/" + (sub ? "subscribe" : "unsubscribe"), data, OptionsUtil.createOptions("weapi"));
    }

    private final String ID_XOR_KEY_1 = "3go8&$8*3*3h0k(2)2";

    private String ncmDllEncodeId(String someId) {
        StringBuilder xoredString = new StringBuilder();

        for (int i = 0; i < someId.length(); i++) {
            char charCode = (char) (someId.charAt(i) ^
                    ID_XOR_KEY_1.charAt(i % ID_XOR_KEY_1.length()));
            xoredString.append(charCode);
        }

        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md5.digest(xoredString.toString().getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @SneakyThrows
    public RequestUtil.RequestAnswer registerAnonimous() {
        String deviceId = DeviceIdGenerator.generate();
        RequestUtil.globalDeviceId = deviceId;

        System.out.println("Device ID: " + deviceId);

        String encodedId = Base64.getEncoder().encodeToString(
                (deviceId + " " + ncmDllEncodeId(deviceId)).getBytes(StandardCharsets.UTF_8)
        );

        Map<String, Object> data = new HashMap<>();
        data.put("username", encodedId);

        RequestUtil.RequestAnswer request = RequestUtil.createRequest("/api/register/anonimous", data, OptionsUtil.createOptions("weapi"));
//        System.out.println(request);
        return request;
    }

    public RequestUtil.RequestAnswer songDetail(long id) {
        return songDetail(Collections.singletonList(id));
    }

    public RequestUtil.RequestAnswer songDetail(List<Long> ids) {

        Map<String, Object> data = new HashMap<>();

        StringBuilder sb = new StringBuilder();

        for (Long id : ids) {
            if (sb.length() != 0)
                sb.append(",");
            sb.append("{\"id\":").append(id).append("}");
        }

        data.put("c", "[" + sb + "]");

        return RequestUtil.createRequest("/api/v3/song/detail", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 每日推荐歌单接口
     */
    public RequestUtil.RequestAnswer recommendResource() {
        return RequestUtil.createRequest("/api/v1/discovery/recommend/resource", null, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 每日推荐歌曲
     */
    public RequestUtil.RequestAnswer recommendSongs() {
        return RequestUtil.createRequest("/api/v3/discovery/recommend/songs", null, OptionsUtil.createOptions("weapi"));
    }

}
