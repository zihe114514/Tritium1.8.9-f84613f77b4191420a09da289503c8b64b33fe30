package com.muoniumplayer.core.ncm.customsource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves metadata only for the exact LX platform explicitly selected by the user. */
final class LxManualSourceMatcher {
    private static final int LIMIT = 8;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private LxManualSourceMatcher() { }

    static Map<String, Object> find(Music music, String platform) throws Exception {
        String key = safe(platform).toLowerCase(Locale.ROOT);
        if ("wy".equals(key)) return official(music, MusicPlatform.NETEASE, key);
        if ("tx".equals(key)) return official(music, MusicPlatform.QQ, key);
        if ("kw".equals(key)) return choose(music, kuwo(music));
        if ("kg".equals(key)) return choose(music, kugou(music));
        if ("mg".equals(key)) return choose(music, migu(music));
        throw new IllegalArgumentException("不支持的平台：" + key);
    }

    private static Map<String, Object> official(Music original, MusicPlatform platform, String key) {
        Music best = null; int score = Integer.MIN_VALUE;
        for (Music candidate : CadenceMusicService.search(platform, keyword(original), LIMIT)) {
            int next = score(original, candidate.getName(), candidate.getArtistsName(), candidate.getDuration());
            if (next > score) { best = candidate; score = next; }
        }
        return best == null || score < 60 ? null : info(key, best.getSourceId(), best.getName(), best.getArtistsName(), best.getDuration(), best.getAlbum() == null ? "" : String.valueOf(best.getAlbum().getId()), best.getAlbum() == null ? "" : best.getAlbum().getName(), "", safe(best.getSourceMid()), "");
    }

    private static List<Map<String, Object>> kuwo(Music music) throws Exception {
        String url = "http://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword(music)) + "&pn=0&rn=" + LIMIT + "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1";
        List<Map<String, Object>> list = new ArrayList<>(); JsonArray items = array(json(url, null), "abslist"); if (items == null) return list;
        for (JsonElement value : items) { JsonObject item = obj(value); if (item == null) continue; String id = text(item, "MUSICRID").replace("MUSIC_", ""); if (!id.isEmpty()) list.add(info("kw", id, text(item, "SONGNAME"), text(item, "ARTIST"), seconds(item, "DURATION"), text(item, "ALBUMID"), text(item, "ALBUM"), "", "", "")); }
        return list;
    }

    private static List<Map<String, Object>> kugou(Music music) throws Exception {
        String url = "https://songsearch.kugou.com/song_search_v2?keyword=" + enc(keyword(music)) + "&page=1&pagesize=" + LIMIT + "&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1";
        List<Map<String, Object>> list = new ArrayList<>(); JsonArray items = array(obj(json(url, null), "data"), "lists"); if (items == null) return list;
        for (JsonElement value : items) { JsonObject item = obj(value); if (item == null) continue; String id = text(item, "Audioid"), hash = text(item, "FileHash"); if (!id.isEmpty() && !hash.isEmpty()) list.add(info("kg", id, text(item, "SongName"), singers(array(item, "Singers")), seconds(item, "Duration"), text(item, "AlbumID"), text(item, "AlbumName"), hash, "", "")); }
        return list;
    }

    private static List<Map<String, Object>> migu(Music music) throws Exception {
        String query = keyword(music), stamp = String.valueOf(System.currentTimeMillis()), device = "963B7AA0D21511ED807EE5846EC87D20";
        String switches = "{\"song\":1,\"album\":0,\"singer\":0,\"tagSong\":1,\"mvSong\":0,\"bestShow\":1,\"songlist\":0,\"lyricSong\":0}";
        String sign = md5(query + "6cdc72a439cef99a3418d2a78aa28c73yyapp2d16148780a1dcc7408e06336b98cfd50" + device + stamp);
        Map<String, String> headers = new HashMap<>(); headers.put("uiVersion", "A_music_3.6.1"); headers.put("deviceId", device); headers.put("timestamp", stamp); headers.put("sign", sign); headers.put("channel", "0146921");
        String url = "https://jadeite.migu.cn/music_search/v3/search/searchAll?isCorrect=0&isCopyright=1&searchSwitch=" + enc(switches) + "&pageSize=" + LIMIT + "&text=" + enc(query) + "&pageNo=1&sort=0&sid=USS";
        List<Map<String, Object>> list = new ArrayList<>(); JsonArray groups = array(obj(json(url, headers), "songResultData"), "resultList"); if (groups == null) return list;
        for (JsonElement group : groups) { if (!group.isJsonArray()) continue; for (JsonElement value : group.getAsJsonArray()) { JsonObject item = obj(value); if (item == null) continue; String id = text(item, "songId"), copyright = text(item, "copyrightId"); if (!id.isEmpty() && !copyright.isEmpty()) list.add(info("mg", id, text(item, "name"), singers(array(item, "singerList")), number(item, "duration"), text(item, "albumId"), text(item, "album"), "", "", copyright)); } }
        return list;
    }

    private static Map<String, Object> choose(Music music, List<Map<String, Object>> values) {
        Map<String, Object> best = null; int score = Integer.MIN_VALUE;
        for (Map<String, Object> value : values) { int next = score(music, str(value, "name"), str(value, "singer"), num(value, "duration")); if (next > score) { score = next; best = value; } }
        return score < 60 ? null : best;
    }

    private static Map<String, Object> info(String source, String id, String name, String singer, long duration, String albumId, String album, String hash, String mediaMid, String copyright) {
        Map<String, Object> value = new HashMap<>(); value.put("source", source); value.put("id", id); value.put("songmid", id); value.put("name", safe(name)); value.put("title", safe(name)); value.put("singer", safe(singer)); value.put("artists", safe(singer)); value.put("duration", Math.max(0L, duration)); value.put("albumId", safe(albumId)); value.put("albumName", safe(album)); value.put("hash", safe(hash)); value.put("strMediaMid", safe(mediaMid)); value.put("copyrightId", safe(copyright)); long sec = Math.max(0L, duration / 1000L); value.put("interval", String.format(Locale.ROOT, "%02d:%02d", sec / 60L, sec % 60L)); return value;
    }

    private static int score(Music original, String name, String singer, long duration) {
        String a = norm(original.getName()), b = norm(name), c = norm(original.getArtistsName()), d = norm(singer); int value = a.equals(b) ? 120 : (a.contains(b) || b.contains(a) ? 70 : 0);
        if (!c.isEmpty() && !d.isEmpty()) value += c.equals(d) ? 50 : (c.contains(d) || d.contains(c) ? 25 : 0);
        if (original.getDuration() > 0 && duration > 0) { long diff = Math.abs(original.getDuration() - duration); if (diff <= 3000) value += 25; else if (diff <= 10000) value += 10; else if (diff > 30000) value -= 40; } return value;
    }

    private static String keyword(Music music) { String name = safe(music == null ? "" : music.getName()), artist = safe(music == null ? "" : music.getArtistsName()); return artist.isEmpty() ? name : name + " " + artist; }
    private static JsonObject json(String address, Map<String, String> headers) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection(); c.setConnectTimeout(7000); c.setReadTimeout(7000); c.setRequestProperty("Accept", "application/json, text/plain, */*"); c.setRequestProperty("User-Agent", AGENT); if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue()); try { int status = c.getResponseCode(); if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status); try (InputStream in = c.getInputStream()) { return new JsonParser().parse(new String(read(in), StandardCharsets.UTF_8)).getAsJsonObject(); } } finally { c.disconnect(); } }
    private static byte[] read(InputStream input) throws Exception { try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] buf = new byte[8192]; int len; while ((len = in.read(buf)) >= 0) { if (out.size() + len > MAX_BYTES) throw new IllegalStateException("匹配响应过大"); out.write(buf, 0, len); } return out.toByteArray(); } }
    private static JsonObject obj(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    private static JsonObject obj(JsonObject value, String key) { return value != null && value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : null; }
    private static JsonArray array(JsonObject value, String key) { return value != null && value.has(key) && value.get(key).isJsonArray() ? value.getAsJsonArray(key) : null; }
    private static String text(JsonObject value, String key) { try { return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : ""; } catch (Throwable ignored) { return ""; } }
    private static long number(JsonObject value, String key) { try { return value != null && value.has(key) ? value.get(key).getAsLong() : 0L; } catch (Throwable ignored) { return 0L; } }
    private static long seconds(JsonObject value, String key) { return number(value, key) * 1000L; }
    private static String singers(JsonArray values) { if (values == null) return ""; StringBuilder result = new StringBuilder(); for (JsonElement value : values) { JsonObject item = obj(value); String name = text(item, "name"); if (name.isEmpty()) name = text(item, "singerName"); if (name.isEmpty()) continue; if (result.length() > 0) result.append('、'); result.append(name); } return result.toString(); }
    private static String str(Map<String, Object> value, String key) { Object item = value.get(key); return item == null ? "" : String.valueOf(item); }
    private static long num(Map<String, Object> value, String key) { try { Object item = value.get(key); return item instanceof Number ? ((Number) item).longValue() : Long.parseLong(String.valueOf(item)); } catch (Throwable ignored) { return 0L; } }
    private static String norm(String value) { return safe(value).toLowerCase(Locale.ROOT).replaceAll("[\\s'~!！@#$%^&*()（）_+\\-=\\[\\]{};；:：,.，。/\\\\|<>《》\"]", ""); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String enc(String value) throws Exception { return URLEncoder.encode(safe(value), "UTF-8"); }
    private static String md5(String value) throws Exception { byte[] bytes = MessageDigest.getInstance("MD5").digest(safe(value).getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(bytes.length * 2); for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return result.toString(); }
}
