package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseRecentPlaysService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small reusable NetEase discovery surface.  It deliberately keeps provider payloads at the
 * boundary and opens the existing PlaylistPanel for playable song collections, so discovery
 * features do not fork the player, lyric, or download pipelines.
 */
public final class NeteaseDiscoveryPanel extends NCMPanel {

    public enum Page {
        HOT_SEARCH("热搜", "网易云实时热搜"),
        DIGITAL_ALBUMS("我的数字专辑", "已购买的数字专辑"),
        TOP_LISTS("排行榜", "网易云音乐榜单"),
        RECENT_SONGS("最近播放", "最近播放的歌曲");

        final String title;
        final String subtitle;

        Page(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private final Page page;
    private final MusicPlatform platform;
    private volatile List<Entry> entries = Collections.emptyList();
    private volatile boolean loading;
    private volatile String status = "";

    public NeteaseDiscoveryPanel(Page page) {
        this(page, MusicPlatform.NETEASE);
    }

    /**
     * Shared discovery surface: QQ routes use the same list, typography and
     * playback pipeline as the existing NetEase page instead of duplicating UI.
     */
    public NeteaseDiscoveryPanel(Page page, MusicPlatform platform) {
        this.page = page == null ? Page.HOT_SEARCH : page;
        this.platform = platform == null ? MusicPlatform.NETEASE : platform;
    }

    @Override
    public void onInit() {
        renderLayout();
        loadPage();
    }

    private void renderLayout() {
        getChildren().clear();
        final double margin = 12.0;

        LabelWidget title = new LabelWidget(() -> pageTitle(), FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setMaxWidth(Math.max(1.0, getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(margin, margin));

        LabelWidget subtitle = new LabelWidget(() -> status.isEmpty() ? pageSubtitle() : status, FontManager.pf12);
        addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> subtitle
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setMaxWidth(Math.max(1.0, getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(margin, 39));

        ScrollPanel list = new ScrollPanel();
        addChild(list);
        list.setSpacing(6);
        list.setScrollStrength(40);
        list.setAlignment(ScrollPanel.Alignment.VERTICAL);
        list.setBeforeRenderCallback(() -> list.setBounds(margin, 60,
                Math.max(1.0, getWidth() - margin * 2.0), Math.max(1.0, getHeight() - 74)));

        if (loading && entries.isEmpty()) {
            LabelWidget loadingText = new LabelWidget("正在获取…", FontManager.pf14bold);
            list.addChild(loadingText);
            loadingText.setClickable(false);
            loadingText.setBeforeRenderCallback(() -> loadingText
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setBounds(Math.max(1.0, list.getWidth()), 22));
            return;
        }

        if (entries.isEmpty()) {
            LabelWidget empty = new LabelWidget(status.isEmpty() ? "暂无可显示内容" : status, FontManager.pf14bold);
            list.addChild(empty);
            empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> empty
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setBounds(Math.max(1.0, list.getWidth()), 22));
            return;
        }

        for (Entry entry : entries) {
            RoundedButtonWidget row = new RoundedButtonWidget(entry::getTitle, FontManager.pf14bold);
            list.addChild(row);
            row.setRadius(5);
            row.setOnClickCallback((x, y, button) -> {
                if (button != 0 || entry.action == null) return button == 0;
                entry.action.run();
                return true;
            });
            row.setBeforeRenderCallback(() -> {
                row.setBounds(Math.max(1.0, list.getWidth()), 28);
                row.setColor(row.isHovering() && entry.action != null
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                row.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            LabelWidget detail = new LabelWidget(entry::getSubtitle, FontManager.pf12);
            row.addChild(detail);
            detail.setClickable(false);
            detail.setBeforeRenderCallback(() -> detail
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setMaxWidth(Math.max(1.0, row.getWidth() - 14))
                    .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                    .setPosition(7, Math.max(14, row.getHeight() - detail.getHeight() - 3)));
        }
    }

    private void loadPage() {
        loading = true;
        status = "正在获取…";
        MultiThreadingUtil.runAsync(() -> {
            try {
                if (platform == MusicPlatform.QQ) {
                    loadQQPage();
                    return;
                }
                if (page == Page.TOP_LISTS) {
                    CardPage cards = topListCards();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (cards.items.isEmpty()) {
                            status = "排行榜暂无内容";
                            renderLayout();
                        } else {
                            NCMScreen.getInstance().setCurrentPanel(new HomePanel(cards.items,
                                    "排行榜 · " + cards.items.size()));
                        }
                    });
                    return;
                }
                if (page == Page.DIGITAL_ALBUMS) {
                    CardPage cards = digitalAlbumCards();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (cards.items.isEmpty()) {
                            status = "暂无已购买数字专辑或登录状态已失效";
                            renderLayout();
                        } else {
                            NCMScreen.getInstance().setCurrentPanel(new HomePanel(cards.items,
                                    "我的数字专辑 · " + cards.items.size(), cards.actions));
                        }
                    });
                    return;
                }
                if (page == Page.RECENT_SONGS) {
                    JsonObject root = CloudMusicApi.recentSongs(100).toJsonObject();
                    List<Music> songs = parseSongsFromRecentResponse(root);
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (songs.isEmpty()) {
                            status = "暂无最近播放记录或登录状态已失效";
                            renderLayout();
                        } else {
                            openSongs(page.title, songs, false);
                        }
                    });
                    return;
                }

                List<Entry> loaded = requestEntries();
                entries = loaded;
                status = loaded.isEmpty() ? "未获取到内容或当前账号无权限" : "共 " + loaded.size() + " 项";
                loading = false;
                MultiThreadingUtil.runOnMainThread(this::renderLayout);
            } catch (Throwable throwable) {
                entries = Collections.emptyList();
                status = "获取失败：" + safeMessage(throwable);
                loading = false;
                MultiThreadingUtil.runOnMainThread(this::renderLayout);
            }
        });
    }

    private void loadQQPage() {
        if (page != Page.TOP_LISTS) {
            entries = Collections.emptyList();
            status = "该 QQ 功能暂未由当前数据源提供";
            loading = false;
            MultiThreadingUtil.runOnMainThread(this::renderLayout);
            return;
        }

        final List<Music> tracks = CadenceMusicService.getQQTopTracks(100);
        List<Entry> loaded = new ArrayList<>();
        for (int index = 0; index < tracks.size(); index++) {
            final int playIndex = index;
            final Music track = tracks.get(index);
            if (track == null) continue;
            String artist = track.getArtistsName() == null ? "" : track.getArtistsName();
            String album = track.getAlbum() == null || track.getAlbum().getName() == null ? "" : track.getAlbum().getName();
            String subtitle = artist + (artist.isEmpty() || album.isEmpty() ? "" : " · ") + album;
            loaded.add(new Entry((index + 1) + ". " + track.getName(), subtitle,
                    () -> CloudMusic.play(tracks, playIndex)));
        }
        entries = loaded;
        status = loaded.isEmpty() ? "QQ 音乐排行榜暂无内容" : "QQ 音乐巅峰榜 · " + loaded.size() + " 首";
        loading = false;
        MultiThreadingUtil.runOnMainThread(this::renderLayout);
    }

    private String pageTitle() {
        if (platform == MusicPlatform.QQ && page == Page.TOP_LISTS) return "QQ 音乐排行榜";
        return page.title;
    }

    private String pageSubtitle() {
        if (platform == MusicPlatform.QQ && page == Page.TOP_LISTS) return "QQ 音乐巅峰榜 · 复用播放器现有列表与播放样式";
        return page.subtitle;
    }

    private List<Entry> requestEntries() {
        switch (page) {
            case HOT_SEARCH:
                return hotEntries();
            default:
                return Collections.emptyList();
        }
    }

    private List<Entry> hotEntries() {
        JsonObject root = CloudMusicApi.searchHotDetail().toJsonObject();
        JsonArray data = array(root, "data");
        List<Entry> result = new ArrayList<>();
        if (data == null) return result;
        int index = 1;
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String keyword = text(item, "searchWord", "first", "keyword");
            if (keyword.isEmpty()) continue;
            String content = text(item, "content", "iconType");
            final String selected = keyword;
            result.add(new Entry(index++ + ". " + keyword, content.isEmpty() ? "点击搜索" : content,
                    () -> openLegacySongSearch(selected)));
        }
        return result;
    }

    /**
     * Hot-search shortcuts deliberately reuse the same single-result-list search flow as the sidebar.
     */
    private static void openLegacySongSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        MultiThreadingUtil.runAsync(() -> {
            List<Music> songs = CloudMusic.search(keyword.trim());
            MultiThreadingUtil.runOnMainThread(() -> openSongs("搜索 · " + keyword.trim(), songs));
        });
    }

    private CardPage digitalAlbumCards() {
        JsonObject root = CloudMusicApi.digitalAlbumPurchased(100, 0).toJsonObject();
        JsonArray data = nestedArray(root, "paidAlbums", "albums", "data");
        List<PlayList> cards = new ArrayList<>();
        Map<Long, Runnable> actions = new HashMap<>();
        if (data == null) return new CardPage(cards, actions);
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject product = element.getAsJsonObject();
            JsonObject album = object(product, "album");
            if (album == null) album = product;
            long id = number(album, "id");
            String name = text(album, "name", "albumName");
            String cover = text(album, "picUrl", "coverImgUrl", "coverUrl");
            if (id <= 0 || name.isEmpty()) continue;
            PlayList card = new PlayList(id, name, cover, (int) Math.min(Integer.MAX_VALUE, number(album, "size", "trackCount")),
                    0L, null, "数字专辑", 0L);
            card.setPlatform(com.muoniumplayer.core.ncm.music.MusicPlatform.NETEASE);
            cards.add(card);
            final long albumId = id;
            actions.put(id, () -> openAlbum(albumId, name));
        }
        return new CardPage(cards, actions);
    }

    private CardPage topListCards() {
        JsonObject root = CloudMusicApi.topListDetail().toJsonObject();
        JsonArray data = nestedArray(root, "list", "data");
        if (data == null) data = nestedArray(CloudMusicApi.topListDetailV2().toJsonObject(), "list", "data");
        if (data == null) data = nestedArray(CloudMusicApi.topLists().toJsonObject(), "list", "data");
        List<PlayList> cards = new ArrayList<>();
        if (data == null) return new CardPage(cards, Collections.emptyMap());
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject list = element.getAsJsonObject();
            long id = number(list, "id");
            String name = text(list, "name");
            String cover = text(list, "coverImgUrl", "coverUrl", "picUrl");
            if (id <= 0 || name.isEmpty()) continue;
            cards.add(new PlayList(id, name, cover, (int) Math.min(Integer.MAX_VALUE, number(list, "trackCount")), 0L,
                    null, text(list, "description", "updateFrequency"), 0L));
        }
        return new CardPage(cards, Collections.emptyMap());
    }

    private static void openAlbum(long albumId, String albumName) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject root = CloudMusicApi.album(albumId).toJsonObject();
                JsonObject privilegeRoot = CloudMusicApi.albumPrivilege(albumId).toJsonObject();
                JsonArray songs = firstSongArray(root, "songs");
                JsonArray privileges = array(privilegeRoot, "privileges", "data");
                if (privileges == null) {
                    JsonObject data = object(privilegeRoot, "data");
                    privileges = array(data, "privileges", "songs", "data");
                }
                List<Music> parsed = parseSongs(songs, privileges);
                MultiThreadingUtil.runOnMainThread(() -> openSongs("专辑 · " + albumName, parsed));
            } catch (Throwable ignored) {
            }
        });
    }

    private static void openPlaylist(long playlistId, String name) {
        try {
            JsonObject item = new JsonObject();
            item.addProperty("id", playlistId);
            item.addProperty("name", name);
            item.addProperty("coverImgUrl", "");
            item.addProperty("trackCount", 0);
            NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(JsonUtils.parse(item, PlayList.class)));
        } catch (Throwable ignored) {
        }
    }

    public static void openSongs(String title, List<Music> songs) {
        openSongs(title, songs, true);
    }

    private static void openSongs(String title, List<Music> songs, boolean showBackButton) {
        CloudMusic.currentPlaylistContext = null;
        PlayList playlist = JsonUtils.parse("{}", PlayList.class);
        playlist.setSearchMode(true);
        playlist.setMusics(new CopyOnWriteArrayList<>(songs == null ? Collections.<Music>emptyList() : songs));
        playlist.setMusicsQueried(true);
        playlist.setMusicsLoaded(true);
        // setCurrentPanel() invokes onInit() exactly once. Calling it again here
        // duplicated every row and made the recent-play page render overlapping text.
        NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playlist, showBackButton));
    }


    /**
     * 最近播放的解析已经搬到 {@link NeteaseRecentPlaysService}：搜索结果起播也要用同一份数据，
     * 两处各写一份解析迟早会分叉。这里保留一层薄封装，本页的调用点不必关心它在哪。
     */
    private static List<Music> parseSongsFromRecentResponse(JsonObject root) {
        return NeteaseRecentPlaysService.parseRecentSongs(root, NeteaseRecentPlaysService.MAX_RECENT_SONGS);
    }

    private static List<Music> parseSongs(JsonArray songs) {
        return parseSongs(songs, null);
    }

    private static List<Music> parseSongs(JsonArray songs, JsonArray privileges) {
        if (songs == null) return Collections.emptyList();
        Map<Long, JsonObject> privilegesBySongId = new HashMap<>();
        if (privileges != null) {
            for (JsonElement privilegeElement : privileges) {
                if (!privilegeElement.isJsonObject()) continue;
                JsonObject privilege = privilegeElement.getAsJsonObject();
                long id = number(privilege, "id");
                if (id <= 0) id = number(privilege, "songId");
                if (id > 0) privilegesBySongId.put(id, privilege);
            }
        }

        List<Music> result = new ArrayList<>();
        for (int index = 0; index < songs.size(); index++) {
            JsonElement element = songs.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            JsonObject actual = object(song, "song");
            if (actual == null) actual = song;
            try {
                Music music = JsonUtils.parse(actual, Music.class);
                JsonObject privilege = privilegesBySongId.get(music.getId());
                if (privilege == null && privileges != null && index < privileges.size() && privileges.get(index).isJsonObject()) {
                    privilege = privileges.get(index).getAsJsonObject();
                }
                // Reuse the existing highest-quality / VIP / album badge metadata path.
                music.applyNeteaseMetadata(actual, privilege);
                result.add(music);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static JsonArray nestedArray(JsonObject root, String... names) {
        JsonArray direct = array(root, names);
        if (direct != null) return direct;
        JsonObject data = object(root, "data");
        direct = array(data, names);
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        return array(result, names);
    }

    private static JsonArray firstSongArray(JsonObject root, String firstKey) {
        JsonArray direct = array(root, firstKey);
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        if (result != null) {
            direct = array(result, firstKey, "songs");
            if (direct != null) return direct;
        }
        return null;
    }

    private static JsonArray array(JsonObject object, String... names) {
        if (object == null) return null;
        for (String name : names) {
            try {
                if (object.has(name) && object.get(name).isJsonArray()) return object.getAsJsonArray(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || name == null || !object.has(name) || !object.get(name).isJsonObject()) return null;
        return object.getAsJsonObject(name);
    }

    private static long number(JsonObject object, String... names) {
        if (object == null || names == null) return 0L;
        for (String name : names) {
            try {
                if (name != null && object.has(name) && !object.get(name).isJsonNull()) {
                    return object.get(name).getAsLong();
                }
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static String text(JsonObject object, String... names) {
        if (object == null) return "";
        for (String name : names) {
            try {
                if (object.has(name) && !object.get(name).isJsonNull()) {
                    String value = object.get(name).getAsString();
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String artistText(JsonObject album) {
        String artist = text(object(album, "artist"), "name");
        if (!artist.isEmpty()) return artist;
        JsonArray artists = array(album, "artists");
        if (artists == null || artists.size() == 0 || !artists.get(0).isJsonObject()) return "";
        return text(artists.get(0).getAsJsonObject(), "name");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "网络请求失败" : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "网络请求失败" : message.trim();
    }

    private static final class CardPage {
        private final List<PlayList> items;
        private final Map<Long, Runnable> actions;

        private CardPage(List<PlayList> items, Map<Long, Runnable> actions) {
            this.items = items == null ? Collections.emptyList() : items;
            this.actions = actions == null ? Collections.emptyMap() : actions;
        }
    }

    private static final class Entry {
        private final String title;
        private final String subtitle;
        private final Runnable action;

        private Entry(String title, String subtitle, Runnable action) {
            this.title = title == null || title.trim().isEmpty() ? "未命名" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.action = action;
        }

        private String getTitle() {
            return title;
        }

        private String getSubtitle() {
            return subtitle;
        }
    }
}
