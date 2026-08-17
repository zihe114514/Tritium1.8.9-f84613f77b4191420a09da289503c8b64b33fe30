package tritium.ncm.music.dto;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import top.fpsmaster.music.Track;
import tritium.ncm.RequestUtil;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.CadenceMusicService;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.MusicPlatform;
import tritium.ncm.music.Quality;
import tritium.utils.Location;
import tritium.utils.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Data
public class Music {

    private static final long PLAY_URL_RESOLVE_TIMEOUT_SECONDS = 10L;
    private static final AtomicInteger URL_RESOLVER_THREAD_ID = new AtomicInteger();
    private static final ExecutorService URL_RESOLVER = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "Music URL Resolver-" + URL_RESOLVER_THREAD_ID.incrementAndGet());
            // 底层网络库即使忽略 interrupt，也不能阻止游戏/IDE 退出。
            thread.setDaemon(true);
            return thread;
        }
    });

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

    /** 跨平台字段。旧网易云 JSON 不包含这些字段时会自然回退到 NETEASE + 数字 id。 */
    private transient MusicPlatform source = MusicPlatform.NETEASE;
    private transient String sourceId;
    private transient String sourceMid;
    private transient String externalCoverUrl;
    private transient boolean vip;
    private transient Track cadenceTrack;

    /** Actual stream quality resolved for the current playback request. */
    private transient volatile PlaybackQuality playbackQuality = PlaybackQuality.UNKNOWN;

    /**
     * Gson may allocate this DTO without running field initializers when it decodes
     * the immutable NetEase response model. Keep the render-facing state non-null
     * even for such deserialized instances.
     */
    public PlaybackQuality getPlaybackQuality() {
        PlaybackQuality current = playbackQuality;
        if (current == null) {
            playbackQuality = PlaybackQuality.UNKNOWN;
            return PlaybackQuality.UNKNOWN;
        }
        return current;
    }

    public enum PlaybackQuality {
        UNKNOWN(""),
        STANDARD(""),
        HQ("HQ"),
        LOSSLESS("无损");

        private final String badge;

        PlaybackQuality(String badge) {
            this.badge = badge;
        }

        public String getBadge() {
            return badge;
        }

        public boolean hasBadge() {
            return !badge.isEmpty();
        }

        /** Human-readable resolved stream tier for runtime status notifications. */
        public String getDisplayName() {
            switch (this) {
                case LOSSLESS:
                    return "无损";
                case HQ:
                    return "HQ";
                case STANDARD:
                    return "标准";
                case UNKNOWN:
                default:
                    return "未知";
            }
        }
    }

    public static Music fromCadenceTrack(Track track) {
        MusicPlatform platform = MusicPlatform.fromCadence(track.getSource());
        String id = nonNull(track.getId());
        long stableId = platform == MusicPlatform.NETEASE
                ? parseNeteaseId(id, track.getMid())
                : stableLong(platform.name() + ':' + id + ':' + nonNull(track.getMid()));

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.getArtists());
        if (!artists.trim().isEmpty()) {
            String[] names = artists.split("\\s*/\\s*|\\s*,\\s*");
            for (String artistName : names) {
                if (!artistName.trim().isEmpty()) {
                    artistList.add(new Artist(stableLong(platform.name() + ":artist:" + artistName),
                            artistName.trim(), Collections.emptyList(), Collections.emptyList()));
                }
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.getAlbum());
        Album album = new Album(stableLong(platform.name() + ":album:" + albumName), albumName,
                nonNull(track.getCoverUrl()), Collections.emptyList());

        Music music = new Music(nonNull(track.getName()), nonNull(track.getName()), "", stableId,
                artistList, Collections.emptyList(), album, track.getDurationMs(), 0L, 0L,
                Collections.emptyList());
        music.source = platform;
        music.sourceId = id;
        music.sourceMid = track.getMid();
        music.externalCoverUrl = track.getCoverUrl();
        music.vip = track.getVip();
        music.cadenceTrack = track;
        return music;
    }

    public Track toCadenceTrack() {
        if (cadenceTrack != null) return cadenceTrack;
        cadenceTrack = new Track(source.toCadenceSource(), getSourceId(), sourceMid, nonNull(name),
                getArtistsName(), album == null ? "" : nonNull(album.getName()), duration,
                getBaseCoverUrl(), vip);
        return cadenceTrack;
    }

    public String getSourceId() {
        return sourceId == null || sourceId.trim().isEmpty() ? String.valueOf(id) : sourceId;
    }

    public MusicPlatform getSource() {
        return source == null ? MusicPlatform.NETEASE : source;
    }

    public boolean isQQ() {
        return getSource() == MusicPlatform.QQ;
    }

    public boolean isNetease() {
        return getSource() == MusicPlatform.NETEASE;
    }

    public String getStableKey() {
        return getSource().name().toLowerCase() + '_' + Long.toUnsignedString(id);
    }

    public final Location getCoverLocation() {
        return Location.of("tritium/textures/music/" + getStableKey() + "/cover.png");
    }

    public final Location getBlurredCoverLocation() {
        return Location.of("tritium/textures/music/" + getStableKey() + "/cover_blurred.png");
    }

    public final Location getSmallCoverLocation() {
        return Location.of("tritium/textures/music/" + getStableKey() + "/cover_small.png");
    }

    public String getArtistsName() {
        if (this.artistsName == null) {
            this.artistsName = this.buildArtistsNames();
            if (this.artistsName.isEmpty()) this.artistsName = "Unknown";
        }
        return this.artistsName;
    }

    public String getTranslatedNames() {
        if (this.translatedNames == null) this.translatedNames = this.buildTranslatedNames();
        return this.translatedNames;
    }

    private String buildTranslatedNames() {
        if (this.translatedName == null || this.translatedName.isEmpty()) return "";
        return String.join(", ", this.translatedName);
    }

    private String buildArtistsNames() {
        if (this.artists == null || this.artists.isEmpty()) return "";
        return this.artists.stream().filter(Objects::nonNull).map(Artist::getName)
                .filter(Objects::nonNull).collect(Collectors.joining(", "));
    }

    private String getBaseCoverUrl() {
        if (externalCoverUrl != null && !externalCoverUrl.trim().isEmpty()) return externalCoverUrl;
        return album == null ? "" : nonNull(album.getPicUrl());
    }

    public String getCoverUrl(int size) {
        String base = getBaseCoverUrl();
        if (base.isEmpty()) return "";
        if (isQQ()) {
            // QQ 封面 URL 已带平台尺寸，避免追加网易云专用 param 导致 404。
            return base.replace("http://", "https://");
        }
        return base + "?param=" + size + "y" + size;
    }

    /** 更新歌曲播放次数；跨平台曲目不调用网易云 scrobble。 */
    @Deprecated
    public void updPlayCount(PlayList pl, float sec) {
        if (!isNetease()) return;
    }

    /**
     * 获得歌曲播放 URL。每一次点歌都会重新解析，不缓存失败结果。
     *
     * 默认配置是无损优先：先请求无损；曲目没有无损、账号没有 VIP、接口返回空链接、
     * 解析异常或超时时，再明确降级到标准音质。网易云在主解析器失败后继续切换
     * 备用解析器；QQ 仅能使用 Cadence，但同样执行无损到标准的降级。
     */
    public Tuple<String, String> getPlayUrl() {
        // Never show a quality badge carried over from a previous failed resolve.
        this.playbackQuality = PlaybackQuality.UNKNOWN;
        if (isQQ()) {
            return resolveCadenceWithStandardFallback();
        }

        // Cadence 搜索得到的网易云歌曲仍优先保持原来源，且该来源内部先尝试无损。
        if (cadenceTrack != null) {
            Tuple<String, String> result = resolveCadenceWithStandardFallback();
            if (result != null) return result;
            return resolveWithTimeout(new Callable<Tuple<String, String>>() {
                @Override
                public Tuple<String, String> call() {
                    return resolveWithBuiltInNeteaseApi();
                }
            }, "built-in NetEase API");
        }

        Tuple<String, String> result = resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithBuiltInNeteaseApi();
            }
        }, "built-in NetEase API");
        if (result != null) return result;
        return resolveCadenceWithStandardFallback();
    }

    /** Tries the configured tier once, then a known-playable standard Cadence stream. */
    private Tuple<String, String> resolveCadenceWithStandardFallback() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        Tuple<String, String> result = resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithCadence(requestedQuality);
            }
        }, "Cadence " + requestedQuality.getQuality());
        if (result != null || requestedQuality == Quality.STANDARD) return result;

        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithCadence(Quality.STANDARD);
            }
        }, "Cadence standard fallback");
    }

    private Tuple<String, String> resolveWithCadence(Quality requestedQuality) {
        Tuple<String, String> resolved = normalizePlayUrl(CadenceMusicService.getSongUrl(this, requestedQuality));
        if (resolved != null) {
            this.playbackQuality = detectCadencePlaybackQuality(resolved.getB(), requestedQuality);
        }
        return resolved;
    }

    private Tuple<String, String> resolveWithBuiltInNeteaseApi() {
        // The default configuration requests lossless first. If the user deliberately
        // picks another tier in the module setting, honor that explicit preference; any
        // unavailable/VIP-gated/failed primary request still falls back to standard MP3.
        Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        Tuple<String, String> preferred = resolveNeteasePlayUrl(
                CloudMusicApi.songUrlV1(this.id, requestedQuality.getQuality().toLowerCase(Locale.ROOT)),
                "requested quality");
        if (preferred != null) return preferred;

        return resolveNeteasePlayUrl(CloudMusicApi.songUrlStandardMp3(this.id), "standard MP3 fallback");
    }

    private Tuple<String, String> resolveNeteasePlayUrl(RequestUtil.RequestAnswer answer, String requestDescription) {
        try {
            if (answer == null || answer.getStatus() != 200) return null;

            JsonObject result = answer.toJsonObject();
            if (result == null || !result.has("data") || !result.get("data").isJsonArray()
                    || result.getAsJsonArray("data").size() == 0) return null;

            JsonObject music = result.getAsJsonArray("data").get(0).getAsJsonObject();
            if ((music.has("code") && !music.get("code").isJsonNull() && music.get("code").getAsInt() != 200)
                    || !music.has("url") || music.get("url").isJsonNull()) return null;

            String url = music.get("url").getAsString();
            if (url == null || url.trim().isEmpty()) return null;

            String type = "mp3";
            if (music.has("type") && !music.get("type").isJsonNull()) {
                String responseType = music.get("type").getAsString();
                if (responseType != null && !responseType.trim().isEmpty()) type = responseType;
            }
            Tuple<String, String> normalized = normalizePlayUrl(new Tuple<>(url, type));
            if (normalized != null) {
                this.playbackQuality = detectNeteasePlaybackQuality(music, normalized.getB());
            }
            return normalized;
        } catch (Throwable throwable) {
            System.err.println("[Music/NetEase] " + requestDescription + " URL resolver failed for "
                    + getStableKey() + ": " + throwable.getMessage());
            return null;
        }
    }
    private Tuple<String, String> resolveWithTimeout(Callable<Tuple<String, String>> resolver, String resolverName) {
        Future<Tuple<String, String>> future = URL_RESOLVER.submit(resolver);
        try {
            return normalizePlayUrl(future.get(PLAY_URL_RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (TimeoutException timeout) {
            future.cancel(true);
            System.err.println("[Music] " + resolverName + " URL resolver timed out for " + getStableKey());
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            future.cancel(true);
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            System.err.println("[Music] " + resolverName + " URL resolver failed for " + getStableKey()
                    + ": " + cause.getMessage());
        }
        return null;
    }

    private PlaybackQuality detectNeteasePlaybackQuality(JsonObject response, String format) {
        if (isLosslessFormat(format)) {
            return PlaybackQuality.LOSSLESS;
        }

        String level = readString(response, "level").toLowerCase(Locale.ROOT);
        if ("lossless".equals(level) || "hires".equals(level)
                || "jyeffect".equals(level) || "jymaster".equals(level)) {
            return PlaybackQuality.LOSSLESS;
        }

        long bitrate = readLong(response, "br");
        if (bitrate >= 256000L || "higher".equals(level) || "exhigh".equals(level)
                || "sky".equals(level)) {
            return PlaybackQuality.HQ;
        }
        return PlaybackQuality.STANDARD;
    }

    private PlaybackQuality detectCadencePlaybackQuality(String format, Quality requestedQuality) {
        if (isLosslessFormat(format)) {
            return PlaybackQuality.LOSSLESS;
        }
        // Cadence does not provide a bitrate for every provider. A lossless request
        // that returns a lossy container is treated as a real standard fallback.
        return (requestedQuality == Quality.HIGHER
                || requestedQuality == Quality.EXHIGH || requestedQuality == Quality.SKY)
                ? PlaybackQuality.HQ : PlaybackQuality.STANDARD;
    }

    private static boolean isLosslessFormat(String format) {
        String normalized = nonNull(format).trim().toLowerCase(Locale.ROOT);
        return "flac".equals(normalized) || "wav".equals(normalized);
    }

    private static String readString(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
        try {
            return nonNull(object.get(property).getAsString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long readLong(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0L;
        try {
            return object.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Tuple<String, String> normalizePlayUrl(Tuple<String, String> result) {
        if (result == null || result.getA() == null || result.getA().trim().isEmpty()) return null;

        String url = result.getA().trim();
        String type = result.getB() == null ? "" : result.getB().trim().toLowerCase(Locale.ROOT);
        int separator = type.indexOf(';');
        if (separator >= 0) type = type.substring(0, separator).trim();
        if (type.startsWith("audio/")) type = type.substring("audio/".length());
        if ("mpeg".equals(type) || "mpeg3".equals(type) || "x-mp3".equals(type)) type = "mp3";
        if ("x-flac".equals(type)) type = "flac";
        if ("x-wav".equals(type) || "wave".equals(type)) type = "wav";

        if (!isSupportedFormat(type)) {
            type = inferFormat(url);
        }
        return isSupportedFormat(type) ? new Tuple<>(url, type) : null;
    }

    private static boolean isSupportedFormat(String type) {
        return "mp3".equals(type) || "flac".equals(type) || "wav".equals(type);
    }

    private static String inferFormat(String url) {
        String clean = nonNull(url).toLowerCase(Locale.ROOT);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 && dot < clean.length() - 1 ? clean.substring(dot + 1) : "";
    }

    public void setLike(boolean like) {
        if (isNetease()) CloudMusicApi.like(this.id, like);
    }

    public boolean isInstrumental() { return (this.featureFlag & INSTRUMENTAL) != 0; }
    public boolean isDolbyAtmos() { return (this.featureFlag & DOLBY_ATMOS) != 0; }
    public boolean isDirty() { return (this.featureFlag & DIRTY) != 0; }
    public boolean isHiRes() { return (this.featureFlag & HIRES) != 0; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Music)) return false;
        Music music = (Music) o;
        return getSource() == music.getSource() && getSourceId().equals(music.getSourceId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSource(), getSourceId());
    }

    private static long parseNeteaseId(String id, String mid) {
        try {
            return Long.parseLong(nonNull(id));
        } catch (NumberFormatException ignored) {
            return stableLong(MusicPlatform.NETEASE.name() + ':' + nonNull(id) + ':' + nonNull(mid));
        }
    }

    private static long stableLong(String value) {
        byte[] bytes = nonNull(value).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
