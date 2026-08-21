package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.screens.ncm.panels.HomePanel;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;

/** Second-level music-source menu for the official providers and GD Studio API sources. */
public final class MusicSourceOverlay extends NCMPanel {

    private boolean closing;
    private boolean choosingGdPlatform;
    private String statusText = "选择一个 GD 音乐台平台后，可直接搜索与播放该平台的曲目";
    private int statusColor = 0xAEB5C4;
    private double presentation;

    public void onInit() {
        showOverview();
    }

    public boolean shouldClose() {
        return closing;
    }

    public void dispose() {
        closing = true;
    }

    public void handleEscape() {
        if (choosingGdPlatform) {
            showOverview();
        } else {
            dispose();
        }
    }

    private void showOverview() {
        choosingGdPlatform = false;
        getChildren().clear();

        Panel dialog = createDialog(452, 342);
        addTitle(dialog, "音乐来源", "官方音乐源与 GD音乐台 在线聚合源可独立切换");

        addSectionLabel(dialog, "官方音乐源", 64);
        addContentSource(dialog, MusicPlatform.NETEASE, 84);
        addContentSource(dialog, MusicPlatform.QQ, 146);

        addSectionLabel(dialog, "在线聚合源", 216);
        addGdContentSource(dialog, 236);

        LabelWidget detail = new LabelWidget("GD音乐台来源由 music-api.gdstudio.xyz 提供；请按上游服务的使用要求使用。", FontManager.pf12);
        dialog.addChild(detail);
        detail.setClickable(false);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(16, dialog.getHeight() - 26);
        });
    }

    private void addGdContentSource(Panel dialog, double y) {
        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        dialog.addChild(card);
        card.setRadius(10);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            showGdPlatforms();
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, dialog.getWidth() - 32), 58);
            card.setPosition(16, y);
            boolean selected = isGdActive();
            card.setColor(selected ? MusicPlatform.GD.getBrandColor()
                    : (card.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
            card.setTextColor(0x00FFFFFF);
        });

        LabelWidget icon = new LabelWidget(FontelloIcons.LINK, FontManager.fontello18);
        dialog.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(0xFFFFFF);
            icon.setPosition(31, y + 18);
        });

        LabelWidget title = new LabelWidget("GD音乐台 · 在线聚合", FontManager.pf14bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(isGdActive() ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(60, y + 13);
        });

        LabelWidget subtitle = new LabelWidget(this::buildGdSummary, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(isGdActive() ? 0xE5FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 174));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(60, y + 34);
        });

        RoundedButtonWidget manage = new RoundedButtonWidget("选择平台", FontManager.pf12bold);
        dialog.addChild(manage);
        manage.setRadius(7);
        manage.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            showGdPlatforms();
            return true;
        });
        manage.setBeforeRenderCallback(() -> {
            manage.setBounds(96, 28);
            manage.setPosition(Math.max(16, dialog.getWidth() - 112), y + 15);
            manage.setColor(manage.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            manage.setTextColor(isGdActive() ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private void showGdPlatforms() {
        choosingGdPlatform = true;
        getChildren().clear();

        Panel dialog = createDialog(510, 430);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, "GD音乐台 · 在线聚合源", "选择一个 API 平台作为当前内容源；再次点击当前平台即可关闭");

        LabelWidget status = new LabelWidget(() -> statusText, FontManager.pf12);
        dialog.addChild(status);
        status.setClickable(false);
        status.setBeforeRenderCallback(() -> {
            status.setColor(statusColor);
            status.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(16, 68);
        });

        ScrollPanel list = new ScrollPanel();
        dialog.addChild(list);
        list.setSpacing(5);
        list.setScrollStrength(20);
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, dialog.getWidth() - 32), Math.max(42, dialog.getHeight() - 122));
            list.setPosition(16, 92);
        });

        for (GdStudioMusicService.Platform platform : GdStudioMusicService.getPlatforms()) {
            addGdPlatformRow(list, platform);
        }
    }

    private void addGdPlatformRow(ScrollPanel list, final GdStudioMusicService.Platform platform) {
        Panel row = new Panel();
        list.addChild(row);
        row.setBeforeRenderCallback(() -> row.setBounds(Math.max(1, list.getWidth()), 54));

        RoundedRectWidget background = new RoundedRectWidget();
        row.addChild(background);
        background.setClickable(false);
        background.setRadius(8);
        background.setBeforeRenderCallback(() -> {
            background.setMargin(0);
            background.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        LabelWidget name = new LabelWidget(platform.displayName, FontManager.pf12bold);
        row.addChild(name);
        name.setClickable(false);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setPosition(10, 9);
        });

        // The static status describes what upstream currently exposes; the runtime status shows a
        // circuit-breaker cooldown so a selected source that just failed explains itself in place.
        LabelWidget description = new LabelWidget(() -> {
            String runtime = GdStudioMusicService.statusLabel(platform.key);
            return platform.key + " · " + (runtime.isEmpty() ? platform.status.description : runtime);
        }, FontManager.pf12);
        row.addChild(description);
        description.setClickable(false);
        description.setBeforeRenderCallback(() -> {
            description.setColor(GdStudioMusicService.statusLabel(platform.key).isEmpty()
                    ? platform.status.color : 0xE07B6B);
            description.setMaxWidth(Math.max(1, row.getWidth() - 96));
            description.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            description.setPosition(10, 30);
        });

        RoundedButtonWidget select = new RoundedButtonWidget(
                () -> platform.key.equals(GdStudioSourceSettings.getPlatform()) ? "关闭" : "选用", FontManager.pf12bold);
        row.addChild(select);
        select.setRadius(6);
        select.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            boolean enable = !platform.key.equals(GdStudioSourceSettings.getPlatform());
            if (!GdStudioSourceSettings.setPlatform(enable ? platform.key : "")) return true;

            if (enable) {
                CadenceMusicService.setCurrentPlatform(MusicPlatform.GD);
                statusText = "已切换到「" + platform.displayName + "」内容源";
                statusColor = 0x75D8A0;
            } else {
                if (CadenceMusicService.getCurrentPlatform() == MusicPlatform.GD) {
                    CadenceMusicService.setCurrentPlatform(MusicPlatform.NETEASE);
                }
                statusText = "已关闭 GD音乐台 内容源";
                statusColor = 0xAEB5C4;
            }
            resetToHomeForCurrentSource();
            showGdPlatforms();
            return true;
        });
        select.setBeforeRenderCallback(() -> {
            select.setBounds(62, 26);
            select.setPosition(Math.max(1, row.getWidth() - 78), 13);
            select.setColor(select.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            select.setTextColor(platform.key.equals(GdStudioSourceSettings.getPlatform())
                    ? 0x75D8A0 : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private boolean isGdActive() {
        return CadenceMusicService.getCurrentPlatform() == MusicPlatform.GD && GdStudioSourceSettings.isEnabled();
    }

    private String buildGdSummary() {
        String key = GdStudioSourceSettings.getPlatform();
        if (key.isEmpty()) return "未选用 · 选择后作为独立内容源使用";
        String label = GdStudioMusicService.displayName(key) + "（" + key + "）";
        // The documented budget is 50 requests / 5 minutes; showing what is left makes the
        // protective throttle visible instead of looking like a random failure.
        String budget = " · 额度剩余 " + GdStudioMusicService.remainingRequestBudget() + "/50";
        return CadenceMusicService.getCurrentPlatform() == MusicPlatform.GD
                ? "当前内容源：" + label + budget : "已选用：" + label + " · 点击切换";
    }

    private void addContentSource(Panel dialog, MusicPlatform platform, double y) {
        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        dialog.addChild(card);
        card.setRadius(10);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            boolean changed = CadenceMusicService.getCurrentPlatform() != platform;
            // Always call the setter: it also clears a stale persisted GD selection when the
            // user clicks the already-selected official source.
            CadenceMusicService.setCurrentPlatform(platform);
            if (changed) {
                resetToHomeForCurrentSource();
            }
            dispose();
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, dialog.getWidth() - 32), 58);
            card.setPosition(16, y);
            boolean selected = CadenceMusicService.getCurrentPlatform() == platform;
            card.setColor(selected ? platform.getBrandColor()
                    : (card.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
            card.setTextColor(0x00FFFFFF);
        });

        LabelWidget icon = new LabelWidget(platform == MusicPlatform.QQ ? MusicBrandIcons.QQ_MUSIC : MusicBrandIcons.NETEASE_CLOUD_MUSIC,
                FontManager.musicBrand18);
        dialog.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(0xFFFFFF);
            icon.setPosition(31, y + 18);
        });

        LabelWidget title = new LabelWidget(platform.getDisplayName(), FontManager.pf14bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(CadenceMusicService.getCurrentPlatform() == platform ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(60, y + 13);
        });

        LabelWidget subtitle = new LabelWidget(platform == MusicPlatform.QQ
                ? "完整内容来源 · 搜索、歌单与 QQ 账号" : "完整内容来源 · 搜索、歌单与网易云账号", FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(CadenceMusicService.getCurrentPlatform() == platform ? 0xE5FFFFFF
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setPosition(60, y + 34);
        });
    }

    private void addSectionLabel(Panel dialog, String text, double y) {
        LabelWidget label = new LabelWidget(text, FontManager.pf12bold);
        dialog.addChild(label);
        label.setClickable(false);
        label.setBeforeRenderCallback(() -> {
            label.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            label.setPosition(16, y);
        });
    }

    /**
     * A provider switch changes the type of content that the current panel can render.
     * Always return to Home before rebuilding the navigation rail instead of leaving a
     * stale search/discovery panel from the previous provider on screen.
     */
    private void resetToHomeForCurrentSource() {
        NCMScreen screen = NCMScreen.getInstance();
        screen.setCurrentPanel(new HomePanel());
        screen.markDirty();
    }

    private Panel createDialog(double preferredWidth, double preferredHeight) {
        presentation = 0.0;
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setColor(0).setAlpha(.48f);
        mask.setClickable(false);
        mask.setBeforeRenderCallback(() -> mask.setMargin(0));
        setOnClickCallback((x, y, button) -> {
            if (button == 0) dispose();
            return button == 0;
        });

        Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            presentation = Interpolations.interpolate(presentation, 1.0, .18f);
            double width = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double height = Math.max(1, Math.min(preferredHeight, getHeight() - 24));
            dialog.setBounds(width, height);
            dialog.setAlpha((float) presentation);
            dialog.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - height * .5 + (1.0 - presentation) * 10.0);
        });

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(13);
        background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        background.setBeforeRenderCallback(() -> background.setMargin(0));
        return dialog;
    }

    private void addTitle(Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(16, 14);
        });
        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(16, 39);
        });
    }

    private void addBackButton(Panel dialog, Runnable action) {
        RoundedButtonWidget back = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        dialog.addChild(back);
        back.setRadius(7);
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        back.setBeforeRenderCallback(() -> {
            back.setBounds(28, 24);
            back.setPosition(16, 13);
            back.setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private void addDetailTitle(Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(56, 14);
        });
        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 72));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(56, 39);
        });
    }
}
