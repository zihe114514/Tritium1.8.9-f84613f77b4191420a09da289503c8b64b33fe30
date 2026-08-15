package tritium.ncm.music.dto;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.CloudMusic;
import tritium.utils.Location;
import tritium.utils.Tuple;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Data
public class Music {

    static final int STEREO = 8192;
    static final int INSTRUMENTAL = 131072;
    static final int DOLBY_ATMOS = 262144;
    static final int DIRTY = 1048576;
    static final long HIRES = 17179869184L;

    @SerializedName("name")
    private final String name;

    @SerializedName("mainTitle")
    private final String mainTitle;

    @SerializedName("additionalTitle")
    private final String additionalTitle;

    @SerializedName("id")
    private final long id;

    @SerializedName("ar")
    private final List<Artist> artists;

    @SerializedName("alia")
    private final List<String> aliasName;

    @SerializedName("al")
    private final Album album;

    @SerializedName("dt")
    private final long duration;

    @SerializedName("mark")
    private final long featureFlag;

    @SerializedName("publishTime")
    private final long publishTime;

    @SerializedName("tns")
    private final List<String> translatedName;

    private transient String artistsName, translatedNames;

    public final Location getCoverLocation() {
        return Location.of("tritium/textures/music/" + this.id + "/cover.png");
    }

    public final Location getBlurredCoverLocation() {
        return Location.of("tritium/textures/music/" + this.id + "/cover_blurred.png");
    }

    public final Location getSmallCoverLocation() {
        return Location.of("tritium/textures/music/" + this.id + "/cover_small.png");
    }

    public String getArtistsName() {
        if (this.artistsName == null) {
            this.artistsName = this.buildArtistsNames();

            if (this.artistsName.isEmpty()) {
                this.artistsName = "Unknown";
            }
        }

        return this.artistsName;
    }

    public String getTranslatedNames() {
        if (this.translatedNames == null) {
            this.translatedNames = this.buildTranslatedNames();
        }

        return this.translatedNames;
    }

    private String buildTranslatedNames() {

        if (this.translatedName == null || this.translatedName.isEmpty())
            return "";

        return String.join(", ", this.translatedName);
    }

    private String buildArtistsNames() {
        List<String> artistsList = this.artists.stream().map(Artist::getName).collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < artistsList.size(); i++) {
            String artistName = artistsList.get(i);

            if (i != artistsList.size() - 1) {
                sb.append(artistName).append(", ");
            } else {
                sb.append(artistName);
            }
        }

        return sb.toString();
    }

    public String getCoverUrl(int size) {
        return this.album.getPicUrl() + "?param=" + size + "y" + size;
    }

    /**
     * 更新歌曲播放次数
     * 这个方法目前会触发网易云风控, 不要使用
     */
    @Deprecated
    public void updPlayCount(PlayList pl, float sec) {
//        MultiThreadingUtil.runAsync(() -> {
//            Map<String, Object> data = new HashMap<>();
//            data.put("id", this.id);
//            data.put("sourceid", pl.id);
//            data.put("time", sec);
//
//            JsonObject result = CloudMusic.api.GET("/scrobble", data).toJson();
//        });
    }

    /**
     * 获得歌曲 url
     *
     * @return 歌曲文件 url
     */
    public Tuple<String, String> getPlayUrl() {
        try {
            JsonObject result = CloudMusicApi.songUrlV1(this.id, CloudMusic.quality.getQuality().toLowerCase()).toJsonObject();
            if (result == null || !result.has("data") || !result.get("data").isJsonArray()
                    || result.getAsJsonArray("data").size() == 0) {
                return null;
            }

            JsonObject music = result.getAsJsonArray("data").get(0).getAsJsonObject();
            if (!music.has("code") || music.get("code").isJsonNull() || music.get("code").getAsInt() != 200
                    || !music.has("url") || music.get("url").isJsonNull()) {
                return null;
            }

            String url = music.get("url").getAsString();
            if (url == null || url.trim().isEmpty()) {
                return null;
            }

            String type = "mp3";
            if (music.has("type") && !music.get("type").isJsonNull()) {
                String responseType = music.get("type").getAsString();
                if (responseType != null && !responseType.trim().isEmpty()) {
                    type = responseType;
                }
            }

            return new Tuple<>(url, type);
        } catch (Exception ignored) {
            // 歌曲 URL 接口在无版权、下架或短暂网络失败时可能没有 data/url，调用方会跳过该曲继续播放。
            return null;
        }
    }

    public void setLike(boolean like) {
        CloudMusicApi.like(this.id, like);
    }

    public boolean isInstrumental() {
        return (this.featureFlag & INSTRUMENTAL) != 0;
    }

    public boolean isDolbyAtmos() {
        return (this.featureFlag & DOLBY_ATMOS) != 0;
    }

    public boolean isDirty() {
        return (this.featureFlag & DIRTY) != 0;
    }

    public boolean isHiRes() {
        return (this.featureFlag & HIRES) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Music music = (Music) o;
        return id == music.id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
