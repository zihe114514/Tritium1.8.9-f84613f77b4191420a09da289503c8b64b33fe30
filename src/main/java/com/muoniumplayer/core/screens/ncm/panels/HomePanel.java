package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author IzumiiKonata
 * Date: 2025/11/4 22:30
 */
public class HomePanel extends NCMPanel {

    private final MusicPlatform platform;
    /** A fixed result set is used by the in-player playlist search without touching homepage caches. */
    private final List<PlayList> fixedPlaylists;
    private final String fixedRecommendationTitle;
    private final Map<Long, Runnable> fixedPlaylistActions;

    public HomePanel() {
        this(null, null, Collections.emptyMap());
    }

    /**
     * Builds a read-only playlist-card page, currently used by NetEase discovery/search pages.
     * The result owns no global cache, so returning home always resumes the normal recommendation page.
     */
    public HomePanel(List<PlayList> playlists, String recommendationTitle) {
        this(playlists, recommendationTitle, Collections.emptyMap());
    }

    /** Allows discovery cards such as digital albums to keep the playlist-card appearance while
     * opening their provider-specific detail action instead of treating an album as a playlist. */
    public HomePanel(List<PlayList> playlists, String recommendationTitle, Map<Long, Runnable> cardActions) {
        super();
        this.platform = CadenceMusicService.getCurrentPlatform();
        this.fixedPlaylists = playlists == null ? null : new ArrayList<>(playlists);
        this.fixedRecommendationTitle = recommendationTitle;
        this.fixedPlaylistActions = cardActions == null ? Collections.emptyMap() : new LinkedHashMap<>(cardActions);
    }

    private static final int NETEASE_HOME_TARGET = 240;
    private static final int NETEASE_PAGE_SIZE = 50;
    private static final ArrayList<PlayList> playLists = new ArrayList<>();
    private static volatile boolean neteaseHomeComplete;
    private static final int QQ_HOME_TARGET = 240;
    private static volatile List<PlayList> qqHomePlaylists = Collections.emptyList();
    private static volatile boolean qqHomeLoading;

    @Override
    public void onInit() {
        if (fixedPlaylists != null) {
            layout(fixedPlaylists, fixedRecommendationTitle == null || fixedRecommendationTitle.trim().isEmpty()
                    ? "歌单搜索" : fixedRecommendationTitle);
            return;
        }
        if (platform == MusicPlatform.QQ) {
            loadQQHome();
        } else {
            loadNeteaseHome();
        }
    }

    private void loadNeteaseHome() {
        List<PlayList> cached;
        synchronized (playLists) {
            cached = new ArrayList<>(playLists);
        }

        // Do not hide already loaded recommendations while the larger public pages are
        // being appended in the background.
        if (!cached.isEmpty()) {
            layout(cached, recommendationTitle(cached.size()));
        }
        if (neteaseHomeComplete) {
            return;
        }

        MultiThreadingUtil.runAsync(() -> {
            LinkedHashMap<Long, PlayList> merged = new LinkedHashMap<>();
            synchronized (playLists) {
                for (PlayList playList : playLists) {
                    if (playList != null) merged.put(playList.getId(), playList);
                }
            }

            // Account-aware daily recommendations stay at the front, but that endpoint
            // intentionally returns only a small set and therefore cannot be the sole
            // homepage source.
            appendNeteasePlaylists(merged, CloudMusicApi.recommendResource(), "recommend");

            boolean personalizedLoaded = appendNeteasePlaylists(
                    merged, CloudMusicApi.personalizedPlaylists(100), "result");

            boolean publicPagesLoaded = true;
            int offset = 0;
            int pagesWithoutNewItems = 0;
            while (merged.size() < NETEASE_HOME_TARGET && offset < NETEASE_HOME_TARGET * 3) {
                int previousSize = merged.size();
                boolean pageLoaded = appendNeteasePlaylists(
                        merged, CloudMusicApi.topPlaylists(NETEASE_PAGE_SIZE, offset), "playlists");
                if (!pageLoaded) {
                    publicPagesLoaded = false;
                    break;
                }

                offset += NETEASE_PAGE_SIZE;
                pagesWithoutNewItems = merged.size() == previousSize
                        ? pagesWithoutNewItems + 1 : 0;
                if (pagesWithoutNewItems >= 2) {
                    break;
                }
            }

            List<PlayList> result = new ArrayList<>(merged.values());
            if (result.size() > NETEASE_HOME_TARGET) {
                result = new ArrayList<>(result.subList(0, NETEASE_HOME_TARGET));
            }

            if (!result.isEmpty()) {
                synchronized (playLists) {
                    playLists.clear();
                    playLists.addAll(result);
                }
            }

            // If a public source failed, keep the cache usable but allow the next visit to
            // retry filling it instead of permanently locking in a partial first response.
            neteaseHomeComplete = personalizedLoaded
                    && publicPagesLoaded
                    && !result.isEmpty();

            final List<PlayList> displayed = result;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (CadenceMusicService.getCurrentPlatform() == platform && !displayed.isEmpty()) {
                    layout(displayed, recommendationTitle(displayed.size()));
                }
            });

            System.out.println("[Music] Loaded " + result.size() + " Netease homepage playlists.");
        });
    }

    private static boolean appendNeteasePlaylists(Map<Long, PlayList> destination,
                                                   com.muoniumplayer.core.ncm.RequestUtil.RequestAnswer answer,
                                                   String arrayName) {
        try {
            if (answer == null || answer.getStatus() < 200 || answer.getStatus() >= 300) {
                return false;
            }

            JsonObject response = answer.toJsonObject();
            JsonElement code = response == null ? null : response.get("code");
            if (response == null || code == null || !code.isJsonPrimitive()
                    || code.getAsInt() != 200 || !response.has(arrayName)
                    || !response.get(arrayName).isJsonArray()) {
                return false;
            }

            JsonArray playlists = response.getAsJsonArray(arrayName);
            for (JsonElement element : playlists) {
                if (!element.isJsonObject()) continue;

                try {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("picUrl") && !object.get("picUrl").isJsonNull()) {
                        object.addProperty("coverImgUrl", object.get("picUrl").getAsString());
                    }
                    if (object.has("playcount") && !object.get("playcount").isJsonNull()) {
                        object.addProperty("playCount", object.get("playcount").getAsLong());
                    }

                    PlayList playList = JsonUtils.parse(object, PlayList.class);
                    if (playList == null || playList.getId() == 0 || playList.getName() == null
                            || playList.getName().trim().isEmpty() || playList.getCoverUrl() == null
                            || playList.getCoverUrl().trim().isEmpty()) {
                        continue;
                    }
                    destination.putIfAbsent(playList.getId(), playList);
                } catch (Throwable ignored) {
                    // One malformed recommendation must not discard every valid item after it.
                }
            }
            return true;
        } catch (Throwable throwable) {
            System.err.println("[Music] Failed to parse Netease homepage page '"
                    + arrayName + "': " + throwable.getMessage());
            return false;
        }
    }

    private static String recommendationTitle(int count) {
        return count > 0 ? "推荐歌单 · " + count : "推荐歌单";
    }
    private void loadQQHome() {
        List<PlayList> cached = new ArrayList<>(qqHomePlaylists);
        if (!cached.isEmpty()) {
            layout(cached, qqRecommendationTitle(cached.size()));
            return;
        }

        synchronized (HomePanel.class) {
            if (qqHomeLoading) return;
            qqHomeLoading = true;
        }

        MultiThreadingUtil.runAsync(() -> {
            List<PlayList> result = Collections.emptyList();
            try {
                // Cadence 已提供 QQ 发现页 GetRecommendFeed，优先展示真实推荐歌单。
                result = CadenceMusicService.getQQRecommendPlaylists(QQ_HOME_TARGET);
                if (result.isEmpty()) {
                    // 推荐接口临时不可用时才回退到排行榜，避免主页整页空白。
                    List<Music> tracks = CadenceMusicService.getQQTopTracks(50);
                    String cover = tracks.isEmpty() ? "" : tracks.get(0).getCoverUrl(256);
                    PlayList top = new PlayList(-260026L,
                            tracks.isEmpty() ? "巅峰热歌榜（暂时无可用曲目）" : "巅峰榜 · 热歌",
                            cover, tracks.size(), 0L, null, "QQ 音乐热门歌曲", 0L);
                    top.setPlatform(MusicPlatform.QQ);
                    top.setPlatformPlaylistId("toplist:26");
                    top.setSearchMode(true);
                    top.setMusics(new CopyOnWriteArrayList<>(tracks));
                    top.setMusicsQueried(true);
                    top.setMusicsLoaded(true);
                    result = Collections.singletonList(top);
                }

                qqHomePlaylists = Collections.unmodifiableList(new ArrayList<>(result));
                final List<PlayList> displayed = new ArrayList<>(result);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (CadenceMusicService.getCurrentPlatform() == platform && !displayed.isEmpty()) {
                        layout(displayed, qqRecommendationTitle(displayed.size()));
                    }
                });
            } finally {
                qqHomeLoading = false;
            }
        });
    }

    private static String qqRecommendationTitle(int count) {
        return count > 1 ? "QQ 音乐 · 推荐歌单 · " + count : "QQ 音乐 · 巅峰榜";
    }
    public ScrollPanel scrollPanel;

    private void layout(List<PlayList> displayedPlayLists, String recommendationsText) {
        this.getChildren().clear();
        final String welcomeText = platform == MusicPlatform.QQ
                ? "欢迎使用 QQ 音乐!" : "欢迎来到 MuoniumPlayer!";
        final int margin = 12;

        // Font atlases report a negative height before their first draw. Keep a stable
        // header reserve from frame one so the responsive card grid can never cover text
        // while the player opens or changes between 100/90/80/70 percent.
        final double welcomeHeight = Math.max(12.0, FontManager.pf25bold.getStringHeight(welcomeText));
        final double recommendationsHeight = Math.max(7.0, FontManager.pf14bold.getStringHeight(recommendationsText));
        final double recommendationsY = margin + welcomeHeight + 2
                + margin * .5 - recommendationsHeight * .5;
        final double contentTop = Math.max(52.0, Math.ceil(recommendationsY + recommendationsHeight + margin * .75));
        // Reserve space for the compact account identity at the player-content lower left.
        final double bottomPadding = 30.0;

        scrollPanel = new ScrollPanel();
        this.addChild(scrollPanel);

        scrollPanel
                .setSpacing(margin)
                .setScrollStrength(44)
                .setAlignment(ScrollPanel.Alignment.VERTICAL_WITH_HORIZONTAL_FILL)
                .setBeforeRenderCallback(() -> scrollPanel.setBounds(
                        margin,
                        contentTop,
                        Math.max(1.0, this.getWidth() - margin * 2.0),
                        Math.max(1.0, this.getHeight() - contentTop - bottomPadding)
                ));

        LabelWidget lblWelcome = new LabelWidget(welcomeText, FontManager.pf25bold);
        lblWelcome.setBeforeRenderCallback(() -> lblWelcome
                .setPosition(margin, margin)
                .setMaxWidth(Math.max(1.0, this.getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));
        this.addChild(lblWelcome);
        // Keep the account identity unobtrusive and out of the title area.  The status lives at
        // the lower-left of the player content and uses the concise "VIP" wording requested.
        LabelWidget lblAccountStatus = new LabelWidget(() -> {
            if (platform == MusicPlatform.QQ) {
                return "QQ · " + CadenceMusicService.getAccountName(MusicPlatform.QQ);
            }
            if (CloudMusic.profile == null) {
                return "网易云 · 未登录";
            }
            return CloudMusic.profile.isVipMember() ? "网易云 · VIP" : "网易云 · 普通";
        }, FontManager.pf12bold);
        lblAccountStatus.setClickable(false);
        lblAccountStatus.setBeforeRenderCallback(() -> {
            boolean vip = platform == MusicPlatform.NETEASE && CloudMusic.profile != null
                    && CloudMusic.profile.isVipMember();
            lblAccountStatus.setColor(vip ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblAccountStatus.setMaxWidth(Math.max(56.0, this.getWidth() * .38));
            lblAccountStatus.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            lblAccountStatus.setPosition(margin, Math.max(margin, this.getHeight() - margin - lblAccountStatus.getHeight()));
        });
        this.addChild(lblAccountStatus);

        LabelWidget lblRecommendations = new LabelWidget(recommendationsText, FontManager.pf14bold);
        lblRecommendations.setBeforeRenderCallback(() -> lblRecommendations
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setMaxWidth(Math.max(1.0, this.getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(margin, recommendationsY));
        this.addChild(lblRecommendations);

        // Headings are intentionally added after the clipped grid so they always remain on top.
        displayedPlayLists.forEach(pl -> scrollPanel.addChild(new PlaylistWidget(pl, fixedPlaylistActions.get(pl.getId())).setShouldOverrideMouseCursor(true)));

    }
    private static class PlaylistWidget extends AbstractWidget<PlaylistWidget> {

        @Getter
        private final PlayList playList;
        private final Runnable customAction;

        double emphasizeAnim = 0;

        boolean coverLoaded = false;

        public PlaylistWidget(PlayList playList) {
            this(playList, null);
        }

        public PlaylistWidget(PlayList playList, Runnable customAction) {
            this.playList = playList;
            this.customAction = customAction;

            double size = 100;
            double emphasizeAnimMax = 5;

            this.setBounds(size + emphasizeAnimMax, size + emphasizeAnimMax + 16);

            RoundedImageWidget cover = new RoundedImageWidget(this::getCoverLocation, 0, 0, size, size);

            this.addChild(cover);

            cover
                    .setClickable(false)
                    .fadeIn()
                    .setLinearFilter(true)
                    .setBeforeRenderCallback(() -> {

                        if (!coverLoaded) {
                            coverLoaded = true;
                            this.loadCover();
                        }

                        this.emphasizeAnim = Interpolations.interpolate(this.emphasizeAnim, cover.isHovering() ? emphasizeAnimMax : 0, .2f);

                        cover
                                .setBounds(size + this.emphasizeAnim)
                                .setRadius(4)
                                .centerHorizontally()
                                .setBounds(cover.getRelativeX(), this.getWidth() * .5 - size * .5 - emphasizeAnim * .5, cover.getWidth(), cover.getHeight());
                    });

            CFontRenderer pf14bold = FontManager.pf14bold;
            LabelWidget lblName = new LabelWidget(() -> fitPlaylistTitle(pf14bold, playList.getName(), size), pf14bold);

            this.addChild(lblName);

            lblName
                    .setClickable(false)
                    .setBeforeRenderCallback(() -> lblName
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                            .setPosition(cover.getRelativeX(), cover.getRelativeY() + cover.getHeight() + 4));

            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    if (customAction != null) customAction.run();
                    else NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList));
                }

                return true;
            });

        }


        @Override
        public void onRender(double mouseX, double mouseY) {

        }

        private static String fitPlaylistTitle(CFontRenderer font, String title, double width) {
            String safeTitle = title == null ? "" : title.trim();
            String[] lines = font.fitWidth(safeTitle, width);
            if (lines.length <= 2) {
                return String.join("\n", lines);
            }

            StringBuilder remaining = new StringBuilder(lines[1]);
            for (int i = 2; i < lines.length; i++) {
                remaining.append(lines[i]);
            }
            return lines[0] + "\n" + font.trim(remaining.toString(), width);
        }
        private void loadCover() {

            TextureManager textureManager = TextureManager.getInstance();
            Location coverLoc = this.getCoverLocation();
            if (textureManager.getTexture(coverLoc) != null)
                return;

            String coverUrl = playList.getCoverUrl();
            if (coverUrl == null || coverUrl.trim().isEmpty()) return;
            String secureUrl = coverUrl.replace("http://", "https://");
            String requestUrl = playList.getPlatform() == MusicPlatform.QQ || playList.isSearchMode()
                    ? secureUrl
                    : secureUrl + "?param=256y256";
            Textures.downloadTextureAndLoadAsync(requestUrl, coverLoc);

        }

        private Location getCoverLocation() {
            return this.playList.getCoverLocation();
        }

    }

}
