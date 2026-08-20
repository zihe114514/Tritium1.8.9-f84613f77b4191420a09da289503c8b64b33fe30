package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.customsource.CustomSourceInfo;
import com.muoniumplayer.core.ncm.customsource.CustomSourceManager;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
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
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.List;

/**
 * Second-level source center. NetEase/QQ remain the two complete content providers; imported LX
 * scripts are presented separately as optional playback resolvers so users never mistake them
 * for a full account/playlist/search service.
 */
public final class MusicSourceOverlay extends NCMPanel {

    private boolean closing;
    private boolean managing;
    private boolean importRunning;
    private String statusText = "选择官方音乐源，或选用一个用于播放回退的自定义音源";
    private int statusColor = 0xAEB5C4;
    private double presentation;

    public void onInit() {
        showOverview();
    }

    public boolean shouldClose() { return closing; }
    public void dispose() { closing = true; }

    public void handleEscape() {
        if (managing) showOverview(); else dispose();
    }

    private void showOverview() {
        managing = false;
        importRunning = false;
        getChildren().clear();
        Panel dialog = createDialog(452, 366);
        addTitle(dialog, "音乐来源", "官方内容与自定义解析分开管理，互不替代");

        addSectionLabel(dialog, "官方音乐源", 64);
        addContentSource(dialog, MusicPlatform.NETEASE, 84);
        addContentSource(dialog, MusicPlatform.QQ, 146);

        LabelWidget resolverTitle = new LabelWidget("自定义源", FontManager.pf12bold);
        dialog.addChild(resolverTitle);
        resolverTitle.setClickable(false);
        resolverTitle.setBeforeRenderCallback(() -> {
            resolverTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            resolverTitle.setPosition(16, 220);
        });

        List<CustomSourceInfo> sources = CustomSourceManager.getSources();
        LabelWidget resolverSummary = new LabelWidget(() -> buildResolverSummary(sources), FontManager.pf12);
        dialog.addChild(resolverSummary);
        resolverSummary.setClickable(false);
        resolverSummary.setBeforeRenderCallback(() -> {
            resolverSummary.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            resolverSummary.setMaxWidth(Math.max(1, dialog.getWidth() - 150));
            resolverSummary.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            resolverSummary.setPosition(16, 242);
        });

        RoundedButtonWidget manage = new RoundedButtonWidget("管理音源", FontManager.pf12bold);
        dialog.addChild(manage);
        manage.setRadius(7);
        manage.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showManagement();
            return true;
        });
        manage.setBeforeRenderCallback(() -> {
            manage.setBounds(112, 28);
            manage.setPosition(Math.max(16, dialog.getWidth() - 128), 231);
            manage.setColor(manage.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            manage.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        LabelWidget detail = new LabelWidget("官方音乐源始终保留主页、搜索、歌单与账号功能；自定义源仅在播放 URL 失败时按用户选用参与解析。", FontManager.pf12);
        dialog.addChild(detail);
        detail.setClickable(false);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(16, dialog.getHeight() - 38);
        });
    }

    private void addContentSource(Panel dialog, MusicPlatform platform, double y) {
        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        dialog.addChild(card);
        card.setRadius(10);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            if (CadenceMusicService.getCurrentPlatform() != platform) {
                CadenceMusicService.setCurrentPlatform(platform);
                NCMScreen.getInstance().markDirty();
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
                platform == MusicPlatform.QQ ? FontManager.musicBrand18 : FontManager.musicBrand18);
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
            boolean selected = CadenceMusicService.getCurrentPlatform() == platform;
            subtitle.setColor(selected ? 0xE5FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setPosition(60, y + 34);
        });
    }

    private void showManagement() {
        managing = true;
        getChildren().clear();
        Panel dialog = createDialog(510, 430);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, "自定义音源管理", "导入 LX 兼容 JavaScript；选用一个音源后，仅作为原官方来源播放失败时的解析回退");

        TextFieldWidget localPath = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(localPath);
        localPath.setPlaceholder("本地 .js 音源路径，例如 D:\\source.js");
        localPath.drawUnderline(false);
        localPath.setBeforeRenderCallback(() -> {
            localPath.setBounds(Math.max(1, dialog.getWidth() - 132), 24);
            localPath.setPosition(16, 68);
            localPath.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        RoundedButtonWidget importLocal = new RoundedButtonWidget("导入本地", FontManager.pf12bold);
        dialog.addChild(importLocal);
        importLocal.setRadius(6);
        importLocal.setOnClickCallback((x, y, button) -> {
            if (button != 0 || importRunning) return false;
            importSource(false, localPath.getText());
            return true;
        });
        importLocal.setBeforeRenderCallback(() -> layoutImportButton(importLocal, dialog, 68));

        TextFieldWidget url = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(url);
        url.setPlaceholder("https://example.com/source.js");
        url.drawUnderline(false);
        url.setBeforeRenderCallback(() -> {
            url.setBounds(Math.max(1, dialog.getWidth() - 132), 24);
            url.setPosition(16, 98);
            url.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        RoundedButtonWidget importUrl = new RoundedButtonWidget("导入网络", FontManager.pf12bold);
        dialog.addChild(importUrl);
        importUrl.setRadius(6);
        importUrl.setOnClickCallback((x, y, button) -> {
            if (button != 0 || importRunning) return false;
            importSource(true, url.getText());
            return true;
        });
        importUrl.setBeforeRenderCallback(() -> layoutImportButton(importUrl, dialog, 98));

        LabelWidget status = new LabelWidget(() -> statusText, FontManager.pf12);
        dialog.addChild(status);
        status.setClickable(false);
        status.setBeforeRenderCallback(() -> {
            status.setColor(statusColor);
            status.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(16, 128);
        });

        ScrollPanel list = new ScrollPanel();
        dialog.addChild(list);
        list.setSpacing(5);
        list.setScrollStrength(20);
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, dialog.getWidth() - 32), Math.max(42, dialog.getHeight() - 176));
            list.setPosition(16, 151);
        });
        buildSourceRows(list);
    }

    private void buildSourceRows(ScrollPanel list) {
        List<CustomSourceInfo> sources = CustomSourceManager.getSources();
        if (sources.isEmpty()) {
            LabelWidget empty = new LabelWidget("尚未导入自定义音源", FontManager.pf14bold);
            list.addChild(empty);
            empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> {
                empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                empty.setBounds(Math.max(1, list.getWidth()), 34);
                empty.setPosition(4, 8);
            });
            return;
        }
        for (CustomSourceInfo info : sources) addSourceRow(list, info);
    }

    private void addSourceRow(ScrollPanel list, CustomSourceInfo info) {
        Panel row = new Panel();
        list.addChild(row);
        row.setBeforeRenderCallback(() -> row.setBounds(Math.max(1, list.getWidth()), 80));
        RoundedRectWidget bg = new RoundedRectWidget();
        row.addChild(bg);
        bg.setClickable(false);
        bg.setRadius(8);
        bg.setBeforeRenderCallback(() -> {
            bg.setMargin(0);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        LabelWidget name = new LabelWidget(() -> info.name + (info.version.isEmpty() ? "" : " · v" + info.version), FontManager.pf12bold);
        row.addChild(name);
        name.setClickable(false);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, row.getWidth() - 232));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(10, 8);
        });
        LabelWidget description = new LabelWidget(() -> info.getDisplayCapabilities() + " · " + info.runtimeStatus, FontManager.pf12);
        row.addChild(description);
        description.setClickable(false);
        description.setBeforeRenderCallback(() -> {
            description.setColor("已就绪".equals(info.runtimeStatus) ? 0x75D8A0 : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            description.setMaxWidth(Math.max(1, row.getWidth() - 232));
            description.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            description.setPosition(10, 29);
        });
        RoundedButtonWidget select = new RoundedButtonWidget(() -> info.selected ? "关闭" : "默认", FontManager.pf12bold);
        row.addChild(select);
        select.setRadius(6);
        select.setOnClickCallback((x, y, button) -> {
            if (button != 0 || !info.enabled) return false;
            boolean selectNow = !info.selected;
            if (CustomSourceManager.select(selectNow ? info.id : null)) {
                statusText = selectNow ? "已使用「" + info.name + "」的默认平台；可在下方手动切换" : "已关闭自定义音源解析";
                statusColor = selectNow ? 0x75D8A0 : 0xAEB5C4;
            } else {
                statusText = "无法选用已停用的音源";
                statusColor = 0xF1767D;
            }
            showManagement();
            return true;
        });
        select.setBeforeRenderCallback(() -> {
            select.setBounds(52, 24); select.setPosition(Math.max(1, row.getWidth() - 212), 15);
            select.setColor(select.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            select.setTextColor(info.selected ? 0x75D8A0 : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        RoundedButtonWidget toggle = new RoundedButtonWidget(() -> info.enabled ? "已启用" : "已停用", FontManager.pf12bold);
        row.addChild(toggle);
        toggle.setRadius(6);
        toggle.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            CustomSourceManager.setEnabled(info.id, !info.enabled);
            statusText = info.enabled ? "已停用「" + info.name + "」" : "正在启用并初始化「" + info.name + "」";
            statusColor = info.enabled ? 0xAEB5C4 : 0x75D8A0;
            showManagement();
            return true;
        });
        toggle.setBeforeRenderCallback(() -> {
            toggle.setBounds(66, 24); toggle.setPosition(Math.max(1, row.getWidth() - 152), 15);
            toggle.setColor(toggle.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            toggle.setTextColor(info.enabled ? 0x75D8A0 : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        RoundedButtonWidget remove = new RoundedButtonWidget("删除", FontManager.pf12bold);
        row.addChild(remove);
        remove.setRadius(6);
        remove.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMScreen.getInstance().openConfirmation("删除自定义音源", "确定删除「" + info.name + "」及其本地脚本吗？", "删除", () -> {
                CustomSourceManager.remove(info.id);
                statusText = "已删除「" + info.name + "」"; statusColor = 0xAEB5C4; showManagement();
            });
            return true;
        });
        remove.setBeforeRenderCallback(() -> {
            remove.setBounds(54, 24); remove.setPosition(Math.max(1, row.getWidth() - 80), 15);
            remove.setColor(remove.isHovering() ? 0x7C3E48 : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            remove.setTextColor(0xF09AA4);
        });

        LabelWidget platformHint = new LabelWidget("手动选择解析平台", FontManager.pf12);
        row.addChild(platformHint);
        platformHint.setClickable(false);
        platformHint.setBeforeRenderCallback(() -> {
            platformHint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            platformHint.setPosition(10, 54);
        });
        int platformIndex = 0;
        for (String declaredSource : info.getDeclaredSources()) {
            final String platformKey = declaredSource;
            final int index = platformIndex++;
            RoundedButtonWidget platform = new RoundedButtonWidget(() -> platformKey.toUpperCase(), FontManager.pf12bold);
            row.addChild(platform);
            platform.setRadius(6);
            platform.setOnClickCallback((x, y, button) -> {
                if (button != 0 || !info.enabled) return false;
                if (CustomSourceManager.select(info.id, platformKey)) {
                    statusText = "已选用「" + info.name + " · " + platformKey.toUpperCase() + "」";
                    statusColor = 0x75D8A0;
                } else {
                    statusText = "该平台尚未就绪或音源已停用";
                    statusColor = 0xF1767D;
                }
                showManagement();
                return true;
            });
            platform.setBeforeRenderCallback(() -> {
                boolean active = info.selected && platformKey.equalsIgnoreCase(info.selectedPlatform);
                platform.setBounds(38, 20);
                platform.setPosition(108 + index * 43, 51);
                platform.setColor(platform.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
                platform.setTextColor(active ? 0x75D8A0 : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });
        }
    }

    private void importSource(boolean network, String value) {
        importRunning = true;
        statusText = network ? "正在下载并校验网络音源…" : "正在读取并校验本地音源…";
        statusColor = 0xAEB5C4;
        MultiThreadingUtil.runAsync(() -> {
            CustomSourceManager.ImportResult result = network
                    ? CustomSourceManager.importFromUrl(value) : CustomSourceManager.importLocal(value);
            MultiThreadingUtil.runOnMainThread(() -> {
                importRunning = false;
                statusText = result.success ? "已导入「" + result.source.name + "」，正在初始化" : result.message;
                statusColor = result.success ? 0x75D8A0 : 0xF1767D;
                showManagement();
            });
        });
    }

    private void layoutImportButton(RoundedButtonWidget button, Panel dialog, double y) {
        button.setBounds(104, 24);
        button.setPosition(Math.max(16, dialog.getWidth() - 120), y);
        button.setColor(importRunning ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)
                : (button.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                : NCMScreen.getColor(NCMScreen.ColorType.ACCENT)));
        button.setTextColor(0xFFFFFF);
    }

    private String buildResolverSummary(List<CustomSourceInfo> sources) {
        CustomSourceInfo selected = CustomSourceManager.getSelectedSource();
        if (selected == null) return sources.isEmpty() ? "未导入 · 可从本地 JS 或网络链接导入" : "当前未选用 · 管理后选择一个音源";
        return "当前选用：" + selected.name + " · " + CustomSourceManager.getSelectedPlatform().toUpperCase() + "（手动选择）";
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

    private Panel createDialog(double preferredWidth, double preferredHeight) {
        presentation = 0.0;
        RectWidget mask = new RectWidget(); addChild(mask); mask.setColor(0).setAlpha(.48f); mask.setClickable(false); mask.setBeforeRenderCallback(() -> mask.setMargin(0));
        setOnClickCallback((x, y, button) -> { if (button == 0) dispose(); return button == 0; });
        Panel dialog = new Panel(); addChild(dialog); dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            presentation = Interpolations.interpolate(presentation, 1.0, .18f);
            double w = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double h = Math.max(1, Math.min(preferredHeight, getHeight() - 24));
            dialog.setBounds(w, h); dialog.setAlpha((float) presentation);
            dialog.setPosition(getWidth() * .5 - w * .5, getHeight() * .5 - h * .5 + (1.0 - presentation) * 10.0);
        });
        RoundedRectWidget background = new RoundedRectWidget(); dialog.addChild(background); background.setClickable(false); background.setRadius(13);
        background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND)); background.setBeforeRenderCallback(() -> background.setMargin(0));
        return dialog;
    }

    private void addTitle(Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold); dialog.addChild(title); title.setClickable(false);
        title.setBeforeRenderCallback(() -> { title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)); title.setPosition(16, 14); });
        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12); dialog.addChild(subtitle); subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> { subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)); subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 32)); subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH); subtitle.setPosition(16, 39); });
    }

    private void addBackButton(Panel dialog, Runnable action) {
        RoundedButtonWidget back = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18); dialog.addChild(back); back.setRadius(7);
        back.setOnClickCallback((x, y, button) -> { if (button != 0) return false; action.run(); return true; });
        back.setBeforeRenderCallback(() -> { back.setBounds(28, 24); back.setPosition(16, 13); back.setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER) : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)); back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)); });
    }

    private void addDetailTitle(Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold); dialog.addChild(title); title.setClickable(false);
        title.setBeforeRenderCallback(() -> { title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)); title.setPosition(56, 14); });
        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12); dialog.addChild(subtitle); subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> { subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)); subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 72)); subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH); subtitle.setPosition(56, 39); });
    }
}