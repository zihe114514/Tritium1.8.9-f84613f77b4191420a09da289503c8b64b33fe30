package tritium.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import org.lwjgl.input.Keyboard;
import tritium.management.FontManager;
import tritium.ncm.api.CloudMusicApi;
import tritium.ncm.music.CadenceMusicService;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.MusicPlatform;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.DownloadDynamicIsland;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.Textures;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.*;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMPlayerConfig;
import tritium.screens.ncm.NCMScreen;
import tritium.screens.ncm.NCMTheme;
import tritium.utils.KeyboardUtils;
import tritium.utils.Location;
import tritium.utils.json.JsonUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 22:00
 */
public class NavigateBar extends NCMPanel {

    TextFieldWidget searchField = new TextFieldWidget(FontManager.pf14bold);
    /**
     * NetEase cloud search supports both tracks and playlists; QQ Cadence currently exposes tracks only.
     */
    private boolean playlistSearchMode;
    ScrollPanel playlistPanel = new ScrollPanel();
    private PlaylistItem homeItem;
    private final List<PlaylistItem> playlistItems = new CopyOnWriteArrayList<>();

    public NavigateBar() {
        this.layout();
    }

    private void layout() {
        RectWidget bg = new RectWidget();
        this.addChild(bg);

        this.setBeforeRenderCallback(() -> {
            double panelWidth = NCMScreen.getInstance().getPanelWidth();
            double responsiveMinimum = Math.min(92.0, panelWidth * .30);
            this.setBounds(Math.max(panelWidth * .15, responsiveMinimum), NCMScreen.getInstance().getPanelHeight());
            this.setPosition(0, 0);

            bg.setMargin(0);
            bg.setColor(this.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND));
            bg.setAlpha(0.9f - NCMTheme.getLiquidGlassAmount() * 0.22f);
        });

        this.setOnKeyTypedCallback((character, keyCode) -> {

            if (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_F)) {
                this.searchField.setFocused(true);
                this.searchField.getTextField().selectAll();
                return true;
            }

            return false;
        });

        RoundedRectWidget searchBar = new RoundedRectWidget();
        RoundedRectWidget searchBarFocusAnimation = new RoundedRectWidget();

        this.addChild(searchBarFocusAnimation);
        this.addChild(searchBar);

        searchBarFocusAnimation.setBeforeRenderCallback(() -> {
            if (!searchField.isFocused()) {
                searchBarFocusAnimation.setAlpha(0);
            } else {
                searchBarFocusAnimation.setAlpha(Interpolations.interpolate(searchBarFocusAnimation.getAlpha(), 1f, .3f));
                searchBarFocusAnimation.setRadius(4);
                searchBarFocusAnimation.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                searchBarFocusAnimation.setBounds(searchBar.getRelativeX(), searchBar.getRelativeY(), searchBar.getWidth(), searchBar.getHeight());
                searchBarFocusAnimation.expand(1 + 5 * (1 - searchBarFocusAnimation.getAlpha()));
            }
        });

        searchBar
//            .setShouldSetMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    searchBar.setAlpha(1f);
                    searchBar.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
                    searchBar.setMargin(8);
                    searchBar.setHeight(16);
                    searchBar.setRadius(3.5);
                });

        RoundedRectWidget searchBarBg = new RoundedRectWidget();
        searchBar.addChild(searchBarBg);

        searchBarBg.setBeforeRenderCallback(() -> {
            searchBarBg.setMargin(.5);
            searchBarBg.setAlpha(.6f);
            searchBarBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            searchBarBg.setRadius(searchBar.getRadius() - .5);
        });

        LabelWidget lblSearchIcon = new LabelWidget("K", FontManager.music18);
        searchBar.addChild(lblSearchIcon);

        lblSearchIcon.setBeforeRenderCallback(() -> {
            lblSearchIcon.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblSearchIcon.centerVertically();
            lblSearchIcon.setPosition(lblSearchIcon.getRelativeY(), lblSearchIcon.getRelativeY());
        });

        RoundedButtonWidget searchTypeToggle = new RoundedButtonWidget(
                () -> playlistSearchMode ? "歌单" : "歌曲", FontManager.pf12bold);
        searchBar.addChild(searchTypeToggle);
        searchTypeToggle.setShouldOverrideMouseCursor(true);
        searchTypeToggle.setBeforeRenderCallback(() -> {
            boolean netease = CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE;
            searchTypeToggle.setHidden(!netease);
            searchTypeToggle.setClickable(netease);
            searchTypeToggle.setBounds(32, 13);
            searchTypeToggle.setPosition(Math.max(0, searchBar.getWidth() - searchTypeToggle.getWidth() - 2), 1.5);
            searchTypeToggle.setRadius(3);
            searchTypeToggle.setColor(playlistSearchMode
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            searchTypeToggle.setTextColor(playlistSearchMode
                    ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        searchTypeToggle.setOnClickCallback((x, y, button) -> {
            if (button != 0 || CadenceMusicService.getCurrentPlatform() != MusicPlatform.NETEASE) {
                return false;
            }
            playlistSearchMode = !playlistSearchMode;
            searchField.setPlaceholder(playlistSearchMode ? "搜索歌单..." : "搜索歌曲...");
            return true;
        });

        searchBar.addChild(searchField);

        this.searchField.setPlaceholder("搜索歌曲...");

        this.searchField.setOnKeyTypedCallback((character, keyCode) -> {
            if (this.searchField.isFocused()) {
                if (keyCode == Keyboard.KEY_ESCAPE)
                    this.searchField.setFocused(false);

                if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                    final String keyword = this.searchField.getText() == null ? "" : this.searchField.getText().trim();
                    if (keyword.isEmpty()) {
                        return true;
                    }

                    this.playlistPanel.getChildren().forEach(child -> {
                        if (child instanceof PlaylistItem) {
                            ((PlaylistItem) child).setSelected(false);
                        }
                    });

                    final boolean searchPlaylists = playlistSearchMode
                            && CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE;
                    if (searchPlaylists) {
                        MultiThreadingUtil.runAsync(() -> {
                            List<PlayList> results = searchNeteasePlaylists(keyword);
                            MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().setCurrentPanel(
                                    new HomePanel(results, "歌单搜索 · " + keyword + " · " + results.size())));
                        });
                    } else {
                        // Keep the original track-search flow and its independent temporary playlist.
                        PlayList playList = JsonUtils.parse("{}", PlayList.class);
                        playList.setSearchMode(true);
                        playList.musics = new CopyOnWriteArrayList<>();
                        PlaylistPanel panel = new PlaylistPanel(playList);
                        NCMScreen.getInstance().setCurrentPanel(panel);
                        MultiThreadingUtil.runAsync(() -> {
                            List<Music> search = CloudMusic.search(keyword);
                            playList.musics.addAll(search);
                            panel.onInit();
                        });
                    }
                }

                return true;
            }

            return false;
        });

        searchField.setBeforeRenderCallback(() -> {
            searchField.drawUnderline(false);
            searchField.setMargin(2);
            double xSpacing = lblSearchIcon.getRelativeX() + lblSearchIcon.getWidth() + 4;
            double typeToggleReserve = CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE ? 36.0 : 0.0;
            searchField.setBounds(xSpacing, searchField.getRelativeY(),
                    Math.max(1.0, searchBar.getWidth() - xSpacing - typeToggleReserve), searchField.getHeight());
            searchField.setColor(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            searchField.setDisabledTextColor(RenderSystem.reAlpha(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .4f));
//            Rect.draw(searchField.getX(), searchField.getY(), searchField.getWidth(), searchField.getHeight(), 0x800090ff);
        });

        Panel sourceSwitcher = new Panel();
        this.addChild(sourceSwitcher);
        sourceSwitcher.setBeforeRenderCallback(() -> {
            sourceSwitcher.setBounds(Math.max(1, this.getWidth() - 16), 18);
            sourceSwitcher.setPosition(8, searchBar.getRelativeY() + searchBar.getHeight() + 5);
        });

        RoundedRectWidget sourceTrack = new RoundedRectWidget();
        sourceTrack.setClickable(false);
        sourceSwitcher.addChild(sourceTrack);
        sourceTrack.setBeforeRenderCallback(() -> {
            sourceTrack.setBounds(0, 0, sourceSwitcher.getWidth(), sourceSwitcher.getHeight());
            sourceTrack.setRadius(6);
            sourceTrack.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            sourceTrack.setAlpha(.72f);
        });

        SourceButton neteaseSource = createSourceButton(MusicPlatform.NETEASE);
        SourceButton qqSource = createSourceButton(MusicPlatform.QQ);
        sourceSwitcher.addChild(neteaseSource);
        sourceSwitcher.addChild(qqSource);
        neteaseSource.setBeforeRenderCallback(() -> layoutSourceButton(neteaseSource, sourceSwitcher, MusicPlatform.NETEASE, false));
        qqSource.setBeforeRenderCallback(() -> layoutSourceButton(qqSource, sourceSwitcher, MusicPlatform.QQ, true));

        this.addChild(playlistPanel);
        this.playlistPanel.setBeforeRenderCallback(() -> {
            this.playlistPanel.setMargin(0);
            double top = sourceSwitcher.getRelativeY() + sourceSwitcher.getHeight() + 7;
            this.playlistPanel.setPosition(this.playlistPanel.getRelativeX(), top);
            // 为紧凑快捷操作栏和账号入口预留底部空间。
            this.playlistPanel.setBounds(this.playlistPanel.getWidth(), Math.max(0, this.getHeight() - top - 70));
        });

        this.playlistPanel.setSpacing(4);

        LabelWidget lbl = new LabelWidget("MuoniumPlayer", FontManager.pf14bold);
        lbl.setBeforeRenderCallback(() -> {
            lbl.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lbl.setPosition(6, lbl.getRelativeY());
        });

        this.playlistPanel.addChild(lbl);

        {
            PlaylistItem item = new PlaylistItem("A", () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT), () -> "主页", () -> NCMScreen.getInstance().setCurrentPanel(new HomePanel()));
            item.setSelected(true);
            this.homeItem = item;

            item.setShouldOverrideMouseCursor(true);

            this.playlistPanel.addChild(item);
        }

        boolean qqMode = CadenceMusicService.getCurrentPlatform() == MusicPlatform.QQ;
        boolean neteaseMode = !qqMode;
        if (neteaseMode) {
            LabelWidget lblDiscovery = new LabelWidget("网易云发现", FontManager.pf14bold);
            lblDiscovery.setClickable(false);
            lblDiscovery.setBeforeRenderCallback(() -> {
                lblDiscovery.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                lblDiscovery.setPosition(6, lblDiscovery.getRelativeY());
            });
            this.playlistPanel.addChild(lblDiscovery);

            this.playlistPanel.addChild(new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                    () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                    new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH))));
            this.playlistPanel.addChild(new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> "排行榜", () -> NCMScreen.getInstance().setCurrentPanel(
                    new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.TOP_LISTS))));
            this.playlistPanel.addChild(new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> "我的数字专辑", () -> NCMScreen.getInstance().setCurrentPanel(
                    new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.DIGITAL_ALBUMS))));
            this.playlistPanel.addChild(new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> "最近播放", () -> NCMScreen.getInstance().setCurrentPanel(
                    new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.RECENT_SONGS))));
        }
        LabelWidget lblPlaylists = new LabelWidget("我的歌单", FontManager.pf14bold);
        lblPlaylists.setBeforeRenderCallback(() -> {
            lblPlaylists.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblPlaylists.setPosition(6, lblPlaylists.getRelativeY());
        });

        this.playlistPanel.addChild(lblPlaylists);

        // QQ 账号歌单由 Cadence 异步加载；网易云继续使用现有 CloudMusic 缓存。
        List<PlayList> pl = qqMode
                ? CadenceMusicService.getQQUserPlaylistsSnapshot()
                : CloudMusic.playLists;

        if (qqMode && CadenceMusicService.isLoggedIn(MusicPlatform.QQ)
                && !CadenceMusicService.areQQUserPlaylistsLoaded()) {
            CadenceMusicService.ensureQQUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }

        // QQ 的创建/收藏歌单统一显示在“我的歌单”下，不再因为平台切换而隐藏整个入口。
        lblPlaylists.setHidden(false);
        if (pl != null && !pl.isEmpty()) {
            if (qqMode) {
                for (PlayList playList : pl) {
                    PlaylistItem item = new PlaylistItem("D",
                            () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                            playList::getName,
                            () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                    item.setPlaylist(playList);
                    this.playlistItems.add(item);
                    item.setShouldOverrideMouseCursor(true);
                    this.playlistPanel.addChild(item);
                }
            } else {
                List<PlayList> playLists = pl.stream()
                        .filter(playList -> !playList.isSubscribed())
                        .collect(java.util.stream.Collectors.toList());
                for (int i = 0; i < playLists.size(); i++) {
                    PlayList playList = playLists.get(i);
                    PlaylistItem item = new PlaylistItem(i == 0 ? "C" : "D",
                            () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                            playList::getName,
                            () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                    item.setPlaylist(playList);
                    this.playlistItems.add(item);
                    item.setShouldOverrideMouseCursor(true);
                    this.playlistPanel.addChild(item);
                }
            }
        } else if (qqMode) {
            String status = !CadenceMusicService.isLoggedIn(MusicPlatform.QQ)
                    ? "登录 QQ 音乐后加载歌单"
                    : (CadenceMusicService.areQQUserPlaylistsLoading()
                    ? "正在加载 QQ 歌单…" : "暂无可用 QQ 歌单");
            PlaylistItem statusItem = new PlaylistItem("·",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> status,
                    () -> NCMScreen.getInstance().openAccountManager());
            statusItem.setShouldOverrideMouseCursor(true);
            this.playlistPanel.addChild(statusItem);
        }

        LabelWidget lblSubscribed = new LabelWidget("收藏歌单", FontManager.pf14bold);
        lblSubscribed.setBeforeRenderCallback(() -> {
            lblSubscribed.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblSubscribed.setPosition(6, lblSubscribed.getRelativeY());
        });

        lblSubscribed.setHidden(qqMode);
        this.playlistPanel.addChild(lblSubscribed);

        if (neteaseMode && pl != null) {
            List<PlayList> subscribedPlaylists = pl.stream()
                    .filter(PlayList::isSubscribed)
                    .collect(java.util.stream.Collectors.toList());
            subscribedPlaylists.forEach(playList -> {
                PlaylistItem item = new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        playList::getName, () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                item.setPlaylist(playList);
                this.playlistItems.add(item);
                item.setShouldOverrideMouseCursor(true);
                this.playlistPanel.addChild(item);
            });
        }
        RoundedRectWidget accountButton = new RoundedRectWidget();
        this.addChild(accountButton);
        accountButton.setRadius(6);
        accountButton.setShouldOverrideMouseCursor(true);
        accountButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMScreen.getInstance().openAccountManager();
            return true;
        });
        accountButton.setBeforeRenderCallback(() -> {
            accountButton.setBounds(Math.max(1, this.getWidth() - 16), 22);
            accountButton.setPosition(8, this.getHeight() - 27);
            accountButton.setColor(accountButton.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            accountButton.setAlpha(accountButton.isHovering() ? .85f : .38f);
        });

        RoundedImageWidget creatorAvatar = new RoundedImageWidget(this::getUserAvatarLocation, 0, 0, 0, 0);
        this.addChild(creatorAvatar);
        creatorAvatar.fadeIn();
        creatorAvatar.setClickable(false);
        creatorAvatar.setLinearFilter(true);
        this.loadAvatar();

        RoundedRectWidget quickActionsSurface = new RoundedRectWidget();
        this.addChild(quickActionsSurface);
        quickActionsSurface.setClickable(false);
        quickActionsSurface.setBeforeRenderCallback(() -> {
            // Keep the original compact action rail dimensions; only the glyph rendering
            // is strengthened so the sidebar layout and button size stay unchanged.
            quickActionsSurface.setBounds(Math.max(1, this.getWidth() - 16), 32);
            quickActionsSurface.setPosition(8, this.getHeight() - 63);
            quickActionsSurface.setRadius(9);
            quickActionsSurface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            quickActionsSurface.setAlpha(.90f);
        });

        SidebarActionButton btnTheme = new SidebarActionButton(SidebarActionIcon.THEME);
        SidebarActionButton btnPlayerSize = new SidebarActionButton(SidebarActionIcon.SCALE);
        SidebarActionButton btnRefresh = new SidebarActionButton(SidebarActionIcon.REFRESH);
        quickActionsSurface.addChild(btnTheme, btnPlayerSize, btnRefresh);

        btnTheme.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0 && mouseButton != 1) return false;
            if (mouseButton == 0) {
                NCMScreen.getInstance().cycleThemeFrom(
                        btnTheme.getX() + btnTheme.getWidth() * .5,
                        btnTheme.getY() + btnTheme.getHeight() * .5);
            }
            DownloadDynamicIsland.showTheme(NCMTheme.getCurrentName());
            return true;
        });

        btnPlayerSize.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) {
                NCMPlayerConfig.cycleScale();
            } else if (mouseButton == 1) {
                NCMPlayerConfig.resetScale();
            } else {
                return false;
            }
            DownloadDynamicIsland.showPlayerScale(NCMPlayerConfig.getPlayerScalePercent());
            return true;
        });

        btnRefresh.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return mouseButton == 1;
            if (CadenceMusicService.getCurrentPlatform() != MusicPlatform.NETEASE) {
                DownloadDynamicIsland.showPlaylistRefreshFailure("QQ 音乐暂不支持歌单刷新");
                return true;
            }
            if (!CloudMusic.beginNeteaseRefresh()) {
                btnRefresh.setSpinning(true);
                DownloadDynamicIsland.showPlaylistRefreshInProgress();
                return true;
            }

            btnRefresh.setSpinning(true);
            DownloadDynamicIsland.showPlaylistRefreshInProgress();
            MultiThreadingUtil.runAsync(() -> {
                CloudMusic.NeteaseRefreshResult result = CloudMusic.refreshNeteaseAccountData();
                MultiThreadingUtil.runOnMainThread(() -> {
                    try {
                        btnRefresh.setSpinning(false);
                        if (result.isSuccess()) {
                            NCMScreen.getInstance().markDirty();
                            // The cloud-song ID set is loaded asynchronously during
                            // the refresh. Rebuild the visible playlist rows so the
                            // newly available "网盘" badge is created immediately.
                            NCMScreen.getInstance().reloadCurrentPanel();
                            DownloadDynamicIsland.showPlaylistRefreshSuccess(
                                    result.getPlaylistCount(), result.getElapsedMillis());
                        } else {
                            DownloadDynamicIsland.showPlaylistRefreshFailure(result.getMessage());
                        }
                    } finally {
                        CloudMusic.endNeteaseRefresh();
                    }
                });
            });
            return true;
        });
        btnTheme.setBeforeRenderCallback(() -> {
            double actionWidth = Math.max(1, (quickActionsSurface.getWidth() - 8) / 3.0);
            btnTheme.setBounds(actionWidth, 28);
            btnTheme.setPosition(2, 2);
            btnTheme.setRadius(7);
            btnTheme.setColor(btnTheme.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        btnPlayerSize.setBeforeRenderCallback(() -> {
            double actionWidth = Math.max(1, (quickActionsSurface.getWidth() - 8) / 3.0);
            btnPlayerSize.setBounds(actionWidth, 28);
            btnPlayerSize.setPosition(4 + actionWidth, 2);
            btnPlayerSize.setRadius(7);
            btnPlayerSize.setColor(btnPlayerSize.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        btnRefresh.setBeforeRenderCallback(() -> {
            double actionWidth = Math.max(1, (quickActionsSurface.getWidth() - 8) / 3.0);
            btnRefresh.setBounds(actionWidth, 28);
            btnRefresh.setPosition(6 + actionWidth * 2, 2);
            btnRefresh.setRadius(7);
            boolean enabled = CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE;
            btnRefresh.setAlpha(enabled ? 1f : .36f);
            btnRefresh.setColor(btnRefresh.isHovering() && enabled
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        creatorAvatar.setBeforeRenderCallback(() -> {
            creatorAvatar.setBounds(16, 16);
            creatorAvatar.setPosition(12, this.getHeight() - 24);
            creatorAvatar.setRadius(7.25);
        });

        LabelWidget lblCreator = new LabelWidget(() -> CadenceMusicService.getCurrentPlatform().getDisplayName() + " · "
                + CadenceMusicService.getAccountName(CadenceMusicService.getCurrentPlatform()), FontManager.pf14bold);
        this.addChild(lblCreator);
        lblCreator.setClickable(false);
        lblCreator.setBeforeRenderCallback(() -> {
            lblCreator.setPosition(creatorAvatar.getRelativeX() + creatorAvatar.getWidth() + 4,
                    creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - lblCreator.getHeight() * .5);
            lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            lblCreator.setMaxWidth(Math.max(1, this.getWidth() - lblCreator.getRelativeX() - 12));
        });
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int mouseButton) {
        return false;
    }

    private void loadAvatar() {
        MusicPlatform platform = CadenceMusicService.getCurrentPlatform();
        String url;
        Location avatarLoc = getUserAvatarLocation();
        if (platform == MusicPlatform.QQ) {
            url = CadenceMusicService.getQQAvatarUrl();
        } else {
            url = CloudMusic.profile == null ? "" : CloudMusic.profile.getAvatarUrl() + "?param=32y32";
        }
        if (avatarLoc == null || url == null || url.trim().isEmpty()) return;
        if (TextureManager.getInstance().getTexture(avatarLoc) == null) {
            Textures.downloadTextureAndLoadAsync(url, avatarLoc);
        }
    }

    private Location getUserAvatarLocation() {
        if (CadenceMusicService.getCurrentPlatform() == MusicPlatform.QQ) {
            return CadenceMusicService.getQQAvatarUrl().isEmpty()
                    ? null : Location.of("tritium/textures/account/qq_avatar.png");
        }
        return CloudMusic.profile == null ? null : CloudMusic.profile.getAvatarLocation();
    }

    private SourceButton createSourceButton(MusicPlatform platform) {
        SourceButton button = new SourceButton(platform);
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            if (CadenceMusicService.getCurrentPlatform() != platform) {
                CadenceMusicService.setCurrentPlatform(platform);
                NCMScreen.getInstance().markDirty();
            }
            return true;
        });
        return button;
    }

    private void layoutSourceButton(SourceButton button, Panel parent, MusicPlatform platform, boolean right) {
        double gap = 3;
        double width = Math.max(1, (parent.getWidth() - gap) * .5);
        button.setBounds(width, parent.getHeight());
        button.setPosition(right ? width + gap : 0, 0);
    }

    private final class SourceButton extends Panel {
        private final MusicPlatform platform;
        private final RoundedRectWidget background = new RoundedRectWidget();
        private final LabelWidget label;

        private SourceButton(MusicPlatform platform) {
            this.platform = platform;
            this.setShouldOverrideMouseCursor(true);

            this.background.setClickable(false);
            this.addChild(this.background);
            this.background.setBeforeRenderCallback(() -> {
                boolean selected = CadenceMusicService.getCurrentPlatform() == this.platform;
                this.background.setBounds(1, 1, Math.max(1, this.getWidth() - 2), Math.max(1, this.getHeight() - 2));
                this.background.setRadius(4.5);
                this.background.setColor(selected
                        ? this.platform.getBrandColor()
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                this.background.setAlpha(selected ? .96f : (this.isHovering() ? .45f : 0f));
            });

            this.label = new LabelWidget(
                    () -> NavigateBar.this.getWidth() < 112
                            ? (this.platform == MusicPlatform.QQ ? "Q" : "N")
                            : (this.platform == MusicPlatform.QQ ? "Q  QQ音乐" : "N  网易云"),
                    FontManager.pf12bold);
            this.label.setClickable(false);
            this.addChild(this.label);
            this.label.setBeforeRenderCallback(() -> {
                this.label.center();
                this.label.setColor(CadenceMusicService.getCurrentPlatform() == this.platform
                        ? 0xFFFFFF
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            });
        }
    }

    private enum SidebarActionIcon {
        THEME,
        SCALE,
        REFRESH
    }

    /**
     * Compact vector icon button that stays legible at every player scale.
     */
    private static final class SidebarActionButton extends RoundedRectWidget {
        private final SidebarActionIcon icon;
        private float hoverAnimation;
        private float pressAnimation;
        private volatile boolean spinning;
        private long lastClickAt;

        private SidebarActionButton(SidebarActionIcon icon) {
            this.icon = icon;
            this.setShouldOverrideMouseCursor(true);
        }

        private void setSpinning(boolean spinning) {
            this.spinning = spinning;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            hoverAnimation = Interpolations.interpolate(hoverAnimation, isHovering() ? 1f : 0f, .22f);
            boolean recentlyPressed = System.currentTimeMillis() - lastClickAt < 170L;
            pressAnimation = Interpolations.interpolate(pressAnimation, recentlyPressed ? 1f : 0f, .30f);

            int base = NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND);
            int hover = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            setColor(mixColor(base, hover, .18f + hoverAnimation * .58f));
            super.onRender(mouseX, mouseY);

            double centerX = getX() + getWidth() * .5;
            double centerY = getY() + getHeight() * .5;
            double iconScale = 1.13 + hoverAnimation * .08 - pressAnimation * .055;
            float alpha = getAlpha() * (.92f + hoverAnimation * .08f);
            // Keep the source colors opaque here. The old implementation pre-applied alpha
            // and then applied it again inside each primitive, which made the glyphs visibly
            // fade into the dark action rail on several themes.
            int accent = getIconAccentColor();
            int foreground = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);

            // Every action now has a persistent contrasting icon plate and a colored bottom
            // marker. This preserves the compact icon-only layout while making the three
            // controls immediately distinguishable before the pointer hovers them.
            roundedRect(centerX - 8.6, centerY - 8.6, 17.2, 17.2, 8.6,
                    RenderSystem.reAlpha(accent, alpha * (.16f + hoverAnimation * .16f)));
            roundedRect(centerX - 7.4, centerY - 7.4, 14.8, 14.8, 7.4,
                    RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND),
                            alpha * (.38f - hoverAnimation * .12f)));
            roundedRect(getX() + 4.0, getY() + getHeight() - 2.4,
                    Math.max(1.0, getWidth() - 8.0), 1.35, .675,
                    RenderSystem.reAlpha(accent, alpha * (.58f + hoverAnimation * .38f)));

            api.getGLStateManager().pushMatrix();
            scaleAtPos(centerX, centerY, iconScale);
            if (icon == SidebarActionIcon.THEME) {
                renderThemeIcon(centerX, centerY, alpha, accent, foreground);
            } else if (icon == SidebarActionIcon.SCALE) {
                renderScaleIcon(centerX, centerY, alpha, accent, foreground);
            } else {
                renderRefreshIcon(centerX, centerY, alpha, accent, foreground);
            }
            api.getGLStateManager().popMatrix();
        }

        @Override
        public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
            lastClickAt = System.currentTimeMillis();
            return super.onMouseClicked(relativeX, relativeY, mouseButton);
        }

        private void renderThemeIcon(double x, double y, float alpha, int accent, int foreground) {
            // A four-color palette mark: larger dots and a bright center remain readable
            // on both dark and liquid-glass themes without relying on a font glyph.
            roundedRect(x - 2.15, y - 6.25, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(accent, alpha));
            roundedRect(x - 6.25, y - 2.15, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFF73B9FF, alpha));
            roundedRect(x + 1.95, y - 2.15, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFFFF86B8, alpha));
            roundedRect(x - 2.15, y + 1.95, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFF75E0B1, alpha));
            roundedRect(x - 1.05, y - 1.05, 2.1, 2.1, 1.05,
                    RenderSystem.reAlpha(foreground, alpha * .92f));
        }

        private void renderRefreshIcon(double x, double y, float alpha, int accent, int foreground) {
            long now = System.currentTimeMillis();
            double rotation = spinning ? (now / 6.0) % 360.0 : -28.0;
            final int segments = 8;
            for (int segment = 0; segment < segments; segment++) {
                // Leave two gaps in the ring to make the refresh silhouette readable.
                if (segment == 2 || segment == 6) continue;
                float segmentAlpha = alpha * (spinning
                        ? (.32f + .68f * (1f - segment / 8f))
                        : .86f);
                api.getGLStateManager().pushMatrix();
                api.getGLStateManager().translate(x, y, 0);
                api.getGLStateManager().rotate((float) (rotation + segment * 45.0), 0, 0, 1);
                roundedRect(-.85, -6.55, 1.7, 3.25, .85,
                        RenderSystem.reAlpha(spinning ? accent : foreground, segmentAlpha));
                api.getGLStateManager().popMatrix();
            }
            // Small arrow tips close the two gaps and turn the ring into a clear
            // refresh symbol rather than a generic loading spinner.
            drawRotatedPill(x - 4.25, y - 4.05, 4.45, 1.65, -42f,
                    RenderSystem.reAlpha(spinning ? accent : foreground, alpha));
            drawRotatedPill(x + 4.25, y + 4.05, 4.45, 1.65, 138f,
                    RenderSystem.reAlpha(spinning ? accent : foreground, alpha));
        }

        private void renderScaleIcon(double x, double y, float alpha, int accent, int foreground) {
            double left = x - 6.15;
            double top = y - 6.15;
            double right = x + 6.15;
            double bottom = y + 6.15;
            double thickness = 1.65;
            double arm = 4.7;
            int corner = RenderSystem.reAlpha(foreground, alpha);
            int highlight = RenderSystem.reAlpha(accent, alpha * .94f);

            roundedRect(left, top, arm, thickness, thickness * .5, highlight);
            roundedRect(left, top, thickness, arm, thickness * .5, highlight);
            roundedRect(right - arm, top, arm, thickness, thickness * .5, corner);
            roundedRect(right - thickness, top, thickness, arm, thickness * .5, corner);
            roundedRect(left, bottom - thickness, arm, thickness, thickness * .5, corner);
            roundedRect(left, bottom - arm, thickness, arm, thickness * .5, corner);
            roundedRect(right - arm, bottom - thickness, arm, thickness, thickness * .5, highlight);
            roundedRect(right - thickness, bottom - arm, thickness, arm, thickness * .5, highlight);
            roundedRect(x - 1.15, y - 1.15, 2.3, 2.3, 1.15,
                    RenderSystem.reAlpha(foreground, alpha * .92f));
        }

        private int getIconAccentColor() {
            if (icon == SidebarActionIcon.SCALE) return 0xFF67D8FF;
            if (icon == SidebarActionIcon.REFRESH) return 0xFF59E3A5;
            return NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
        }

        private void drawRotatedPill(double centerX, double centerY, double width, double height,
                                     float rotation, int color) {
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate(rotation, 0, 0, 1);
            roundedRect(-width * .5, -height * .5, width, height, height * .5, color);
            api.getGLStateManager().popMatrix();
        }

        private int mixColor(int first, int second, float amount) {
            float t = Math.max(0f, Math.min(1f, amount));
            int a = (int) (((first >>> 24) & 255) * (1f - t) + ((second >>> 24) & 255) * t);
            int r = (int) (((first >>> 16) & 255) * (1f - t) + ((second >>> 16) & 255) * t);
            int g = (int) (((first >>> 8) & 255) * (1f - t) + ((second >>> 8) & 255) * t);
            int b = (int) ((first & 255) * (1f - t) + (second & 255) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    /**
     * Re-selects the sidebar item that represents the currently visible content panel.
     */
    public void selectCurrentPanel(NCMPanel panel) {
        PlaylistItem selectedItem = null;
        if (panel instanceof HomePanel) {
            selectedItem = this.homeItem;
        } else if (panel instanceof PlaylistPanel) {
            PlayList activePlaylist = ((PlaylistPanel) panel).playList;
            if (activePlaylist != null && !activePlaylist.isSearchMode()) {
                for (PlaylistItem item : this.playlistItems) {
                    if (activePlaylist.equals(item.getPlaylist())) {
                        selectedItem = item;
                        break;
                    }
                }
            }
        }

        if (this.homeItem != null) {
            this.homeItem.setSelected(this.homeItem == selectedItem);
        }
        for (PlaylistItem item : this.playlistItems) {
            item.setSelected(item == selectedItem);
        }
    }

    @Override
    public void onInit() {

    }

    public static class PlaylistItem extends Panel {

        String icon;
        Supplier<Integer> iconColorSupplier;
        Supplier<String> label;
        Runnable onClick;
        RoundedRectWidget bg = new RoundedRectWidget();

        @Getter
        @Setter
        boolean selected = false;

        @Getter
        @Setter
        private PlayList playlist;

        public PlaylistItem(String icon, Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this.icon = icon;
            this.iconColorSupplier = iconColorSupplier;
            this.label = label;
            this.onClick = onClick;
            this.setBeforeRenderCallback(() -> {
                // The item is inset by four pixels on both sides. Subtract the insets from
                // its live width as the player scales so neither background nor text can
                // extend past the navigation viewport.
                this.setBounds(Math.max(1, this.getParentWidth() - 8), 16);
                this.setPosition(4, this.getRelativeY());
            });

            bg.setClickable(false);

            this.addChild(bg);
            this.bg.setBeforeRenderCallback(() -> {
                bg.setMargin(0);
                bg.setHidden(!selected);
                bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                bg.setAlpha(selected ? 0.2f : 0f);
                bg.setRadius(4);
            });

            LabelWidget lblIcon = new LabelWidget(icon, FontManager.music18);
            this.addChild(lblIcon);
            lblIcon.setBeforeRenderCallback(() -> {
                lblIcon.setColor(iconColorSupplier.get());
                lblIcon.centerVertically();
                lblIcon.setPosition(8, lblIcon.getRelativeY()/* + .5*/);
            });

            lblIcon.setClickable(false);

            LabelWidget lbl = new LabelWidget(label, FontManager.pf14bold);
            this.addChild(lbl);

            lbl.setBeforeRenderCallback(() -> {
                lbl.centerVertically();
                lbl.setPosition(lblIcon.getRelativeX() + lblIcon.getWidth() + 4, lbl.getRelativeY());
                lbl.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lbl.setMaxWidth(this.getWidth() - 8 - lblIcon.getWidth() - 12);
            });

            lbl.setClickable(false);

            this.setOnClickCallback(((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    this.selected = true;
                    bg.setHidden(false);

                    this.onClick.run();

                    NCMScreen.getInstance().getPlaylistsPanel().playlistPanel.getChildren().stream()
                            .filter(it -> it instanceof PlaylistItem && it != this)
                            .forEach(it -> ((PlaylistItem) it).setSelected(false));
                }

                return true;
            }));
        }

    }

    /**
     * Executes the documented NetEase cloud-search playlist endpoint (type 1000).
     */
    private List<PlayList> searchNeteasePlaylists(String keyword) {
        List<PlayList> results = new ArrayList<>();
        try {
            JsonObject response = CloudMusicApi.cloudSearch(keyword, CloudMusicApi.SearchType.Playlist).toJsonObject();
            if (response == null || !response.has("result") || !response.get("result").isJsonObject()) {
                return results;
            }
            JsonObject result = response.getAsJsonObject("result");
            JsonArray playlists = result.getAsJsonArray("playlists");
            if (playlists == null) {
                return results;
            }

            for (JsonElement element : playlists) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                try {
                    JsonObject object = element.getAsJsonObject();
                    if ((!object.has("coverImgUrl") || object.get("coverImgUrl").isJsonNull())
                            && object.has("picUrl") && !object.get("picUrl").isJsonNull()) {
                        object.addProperty("coverImgUrl", object.get("picUrl").getAsString());
                    }
                    if ((!object.has("playCount") || object.get("playCount").isJsonNull())
                            && object.has("playcount") && !object.get("playcount").isJsonNull()) {
                        object.addProperty("playCount", object.get("playcount").getAsLong());
                    }
                    PlayList playlist = JsonUtils.parse(object, PlayList.class);
                    if (playlist == null || playlist.getId() == 0L || playlist.getName() == null
                            || playlist.getName().trim().isEmpty() || playlist.getCoverUrl() == null
                            || playlist.getCoverUrl().trim().isEmpty()) {
                        continue;
                    }
                    playlist.setPlatform(MusicPlatform.NETEASE);
                    results.add(playlist);
                } catch (Throwable ignored) {
                    // A malformed item cannot invalidate the remaining search results.
                }
            }
        } catch (Throwable throwable) {
            System.err.println("[Music] NetEase playlist search failed: " + throwable.getMessage());
        }
        return results;
    }

}
