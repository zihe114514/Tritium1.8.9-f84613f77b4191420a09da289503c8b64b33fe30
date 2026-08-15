package tritium.screens.ncm.panels;

import lombok.Getter;
import lombok.Setter;
import org.lwjgl.input.Keyboard;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 22:00
 */
public class NavigateBar extends NCMPanel {

    TextFieldWidget searchField = new TextFieldWidget(FontManager.pf14bold);
    ScrollPanel playlistPanel = new ScrollPanel();

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

        searchBar.addChild(searchField);

        this.searchField.setPlaceholder("搜索...");

        this.searchField.setOnKeyTypedCallback((character, keyCode) -> {
            if (this.searchField.isFocused()) {
                if (keyCode == Keyboard.KEY_ESCAPE)
                    this.searchField.setFocused(false);

                if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {

                    // new instance
                    PlayList playList = JsonUtils.parse("{}", PlayList.class);
                    playList.setSearchMode(true);
                    playList.musics = new CopyOnWriteArrayList<>();
                    PlaylistPanel panel = new PlaylistPanel(playList);
                    NCMScreen.getInstance().setCurrentPanel(panel);
                    this.playlistPanel.getChildren().forEach(child -> {
                        if (child instanceof PlaylistItem)
                            ((PlaylistItem) child).setSelected(false);
                    });

                    MultiThreadingUtil.runAsync(() -> {
                        List<Music> search = CloudMusic.search(this.searchField.getText());
                        playList.musics.addAll(search);
                        panel.onInit();
                    });
//
//                    System.out.println("SEARCH: " + this.searchField.getText());
//                    CloudMusicApi.cloudSearch(this.searchField.getText(), CloudMusicApi.SearchType.Single).toJsonObject()
                }

                return true;
            }

            return false;
        });

        searchField.setBeforeRenderCallback(() -> {
            searchField.drawUnderline(false);
            searchField.setMargin(2);
            double xSpacing = lblSearchIcon.getRelativeX() + lblSearchIcon.getWidth() + 4;
            searchField.setBounds(xSpacing, searchField.getRelativeY(), searchField.getWidth() - xSpacing, searchField.getHeight());
            searchField.setColor(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            searchField.setDisabledTextColor(RenderSystem.reAlpha(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .4f));
//            Rect.draw(searchField.getX(), searchField.getY(), searchField.getWidth(), searchField.getHeight(), 0x800090ff);
        });

        this.addChild(playlistPanel);
        this.playlistPanel.setBeforeRenderCallback(() -> {
            this.playlistPanel.setMargin(0);
            this.playlistPanel.setPosition(this.playlistPanel.getRelativeX(), searchBar.getRelativeY() + searchBar.getHeight() + 8);
            // 为播放器大小调节、主题按钮和用户信息预留底部空间。
            this.playlistPanel.setBounds(this.playlistPanel.getWidth(), this.playlistPanel.getHeight() - searchBar.getHeight() - 16 - 82);
        });

        this.playlistPanel.setSpacing(4);

        LabelWidget lbl = new LabelWidget("Tritium Music", FontManager.pf14bold);
        lbl.setBeforeRenderCallback(() -> {
            lbl.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lbl.setPosition(6, lbl.getRelativeY());
        });

        this.playlistPanel.addChild(lbl);

        {
            PlaylistItem item = new PlaylistItem("A", () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT), () -> "主页", () -> NCMScreen.getInstance().setCurrentPanel(new HomePanel()));

            item.setShouldOverrideMouseCursor(true);

            this.playlistPanel.addChild(item);
        }

        LabelWidget lblPlaylists = new LabelWidget("我的歌单", FontManager.pf14bold);
        lblPlaylists.setBeforeRenderCallback(() -> {
            lblPlaylists.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblPlaylists.setPosition(6, lblPlaylists.getRelativeY());
        });

        this.playlistPanel.addChild(lblPlaylists);

        List<PlayList> pl = CloudMusic.playLists;

        if (pl != null) {
            List<PlayList> playLists = pl.stream().filter(playList -> !playList.isSubscribed()).collect(java.util.stream.Collectors.toList());
            for (int i = 0; i < playLists.size(); i++) {
                PlayList playList = playLists.get(i);
                PlaylistItem item = new PlaylistItem(i == 0 ? "C" : "D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), playList::getName, () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                item.setShouldOverrideMouseCursor(true);

                this.playlistPanel.addChild(item);
            }
        }

        LabelWidget lblSubscribed = new LabelWidget("收藏歌单", FontManager.pf14bold);
        lblSubscribed.setBeforeRenderCallback(() -> {
            lblSubscribed.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblSubscribed.setPosition(6, lblSubscribed.getRelativeY());
        });

        this.playlistPanel.addChild(lblSubscribed);

        if (pl != null) {
            pl.stream().filter(PlayList::isSubscribed).forEach(playList -> {
                PlaylistItem item = new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), playList::getName, () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                item.setShouldOverrideMouseCursor(true);

                this.playlistPanel.addChild(item);
            });
        }

        RoundedImageWidget creatorAvatar = new RoundedImageWidget(this.getUserAvatarLocation(), 0, 0, 0, 0);
        this.addChild(creatorAvatar);
        creatorAvatar.fadeIn();
        creatorAvatar.setLinearFilter(true);

        this.loadAvatar();

        RoundedButtonWidget btnPlayerSize = new RoundedButtonWidget(
                () -> (this.getWidth() < 88 ? "大小 · " : "播放器大小 · ") + NCMPlayerConfig.getPlayerScalePercent() + "%", FontManager.pf12bold
        );
        this.addChild(btnPlayerSize);
        btnPlayerSize.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) {
                NCMPlayerConfig.cycleScale();
            } else if (mouseButton == 1) {
                NCMPlayerConfig.resetScale();
            }
            return true;
        });
        btnPlayerSize.setBeforeRenderCallback(() -> {
            btnPlayerSize.setBounds(Math.max(1, this.getWidth() - 16), 18);
            btnPlayerSize.setPosition(8, this.getHeight() - 72);
            btnPlayerSize.setRadius(5);
            btnPlayerSize.setColor(btnPlayerSize.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            btnPlayerSize.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        RoundedButtonWidget btnTheme = new RoundedButtonWidget(
                () -> "主题 · " + NCMTheme.getCurrentName(), FontManager.pf12bold
        );
        this.addChild(btnTheme);
        btnTheme.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) {
                NCMScreen.getInstance().cycleThemeFrom(
                        btnTheme.getX() + btnTheme.getWidth() * .5,
                        btnTheme.getY() + btnTheme.getHeight() * .5);
            }
            return true;
        });
        btnTheme.setBeforeRenderCallback(() -> {
            btnTheme.setBounds(Math.max(1, this.getWidth() - 16), 18);
            btnTheme.setPosition(8, this.getHeight() - 50);
            btnTheme.setRadius(5);
            btnTheme.setColor(btnTheme.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            btnTheme.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        creatorAvatar.setBeforeRenderCallback(() -> {
            creatorAvatar.setBounds(16, 16);
            creatorAvatar.setPosition(12, this.getHeight() - 8 - creatorAvatar.getHeight());
            creatorAvatar.setRadius(7.25);
        });

        LabelWidget lblCreator = new LabelWidget(() -> CloudMusic.profile == null ? "未登录" : CloudMusic.profile.getName(), FontManager.pf16bold);
        this.addChild(lblCreator);

        lblCreator.setBeforeRenderCallback(() -> {
            lblCreator.setPosition(creatorAvatar.getRelativeX() + creatorAvatar.getWidth() + 4, creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - lblCreator.getHeight() * .5);
            lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int mouseButton) {
        return false;
    }

    private void loadAvatar() {

        if (CloudMusic.profile == null) {
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        Location avatarLoc = this.getUserAvatarLocation();
        if (textureManager.getTexture(avatarLoc) != null)
            return;

        Textures.downloadTextureAndLoadAsync(CloudMusic.profile.getAvatarUrl() + "?param=32y32", avatarLoc);
    }

    private Location getUserAvatarLocation() {
        if (CloudMusic.profile == null) {
            return null;
        }

        return CloudMusic.profile.getAvatarLocation();
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

        public PlaylistItem(String icon, Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this.icon = icon;
            this.iconColorSupplier = iconColorSupplier;
            this.label = label;
            this.onClick = onClick;

            this.setBeforeRenderCallback(() -> {
                this.setBounds(this.getParentWidth(), 16);
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
}
