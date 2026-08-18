package tritium.screens.ncm.panels;

import tritium.management.FontManager;
import tritium.ncm.music.PersonalFmManager;
import tritium.ncm.music.dto.Music;
import tritium.ncm.music.dto.PlayList;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.ScrollPanel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.RoundedButtonWidget;
import tritium.screens.ncm.NCMPanel;
import tritium.screens.ncm.NCMScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Personal-FM discovery surface backed by one-at-a-time recommendation pulls. */
public final class PersonalFmPanel extends NCMPanel {

    private static final double MARGIN = 12.0;

    @Override
    public void onInit() {
        renderLayout();
        PersonalFmManager.ensureInitialLoad();
    }

    private void renderLayout() {
        getChildren().clear();

        RoundedButtonWidget back = new RoundedButtonWidget("返回", FontManager.pf12bold);
        addChild(back);
        back.setShouldOverrideMouseCursor(true);
        back.setBeforeRenderCallback(() -> {
            back.setBounds(42, 16).setPosition(MARGIN, 8).setRadius(4)
                    .setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMScreen.getInstance().navigateBack();
            return true;
        });

        LabelWidget title = new LabelWidget("私人 FM", FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(MARGIN, 31)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH));

        LabelWidget subtitle = new LabelWidget(PersonalFmManager::getStatus, FontManager.pf12);
        addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> subtitle
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(MARGIN, 56)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL));

        RoundedButtonWidget refresh = new RoundedButtonWidget("刷新", FontManager.pf12bold);
        addChild(refresh);
        refresh.setShouldOverrideMouseCursor(true);
        refresh.setBeforeRenderCallback(() -> {
            refresh.setBounds(40, 17)
                    .setPosition(Math.max(MARGIN, getWidth() - MARGIN - refresh.getWidth()), 31)
                    .setRadius(4)
                    .setClickable(!PersonalFmManager.isLoading())
                    .setAlpha(PersonalFmManager.isLoading() ? .45f : 1.0f)
                    .setColor(refresh.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            refresh.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        refresh.setOnClickCallback((x, y, button) -> {
            if (button != 0 || PersonalFmManager.isLoading()) return button == 0;
            PersonalFmManager.openOrRefresh();
            return true;
        });

        PersonalFmManager.Mode[] modes = {
                PersonalFmManager.Mode.DEFAULT, PersonalFmManager.Mode.FAMILIAR,
                PersonalFmManager.Mode.EXPLORE, PersonalFmManager.Mode.SCENE_RCMD,
                PersonalFmManager.Mode.AIDJ
        };
        for (int index = 0; index < modes.length; index++) addModeButton(modes[index], index);

        if (PersonalFmManager.getSelectedMode() == PersonalFmManager.Mode.SCENE_RCMD) {
            addSceneButton("运动", PersonalFmManager.SCENE_EXERCISE, 0);
            addSceneButton("专注", PersonalFmManager.SCENE_FOCUS, 1);
            addSceneButton("夜间", PersonalFmManager.SCENE_NIGHT_EMO, 2);
        }

        final double controlsBottom = PersonalFmManager.getSelectedMode() == PersonalFmManager.Mode.SCENE_RCMD ? 112.0 : 92.0;
        ScrollPanel songs = new ScrollPanel();
        addChild(songs);
        songs.setSpacing(0).setScrollStrength(42).setAlignment(ScrollPanel.Alignment.VERTICAL);
        songs.setBeforeRenderCallback(() -> songs.setBounds(MARGIN, controlsBottom,
                Math.max(1.0, getWidth() - MARGIN * 2.0), Math.max(1.0, getHeight() - controlsBottom - 8.0)));

        List<Music> batch = PersonalFmManager.getCurrentBatchSnapshot();
        if (batch.isEmpty()) {
            LabelWidget empty = new LabelWidget(PersonalFmManager.isLoading() ? "正在获取推荐…" : "暂无私人 FM 推荐", FontManager.pf14bold);
            songs.addChild(empty);
            empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> empty
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setBounds(Math.max(1.0, songs.getWidth()), 24));
            return;
        }

        PlayList fmPlaylist = new PlayList(-900001L, "私人 FM", "", batch.size(), 0L, null, "个人推荐", 0L);
        fmPlaylist.setSearchMode(true);
        fmPlaylist.setPersonalFm(true);
        fmPlaylist.setMusics(new CopyOnWriteArrayList<>(batch));
        fmPlaylist.setMusicsQueried(true);
        fmPlaylist.setMusicsLoaded(true);

        List<AbstractWidget<?>> rows = new ArrayList<>();
        for (int index = 0; index < batch.size(); index++) {
            MusicWidget row = new MusicWidget(batch.get(index), fmPlaylist, index);
            row.setShouldOverrideMouseCursor(true);
            rows.add(row);
        }
        songs.addChild(rows);
    }

    private void addModeButton(PersonalFmManager.Mode mode, int index) {
        RoundedButtonWidget button = new RoundedButtonWidget(mode.getDisplayName(), FontManager.pf12bold);
        addChild(button);
        button.setShouldOverrideMouseCursor(true);
        button.setBeforeRenderCallback(() -> {
            boolean selected = PersonalFmManager.getSelectedMode() == mode;
            double width = Math.max(31.0, FontManager.pf12bold.getStringWidth(mode.getDisplayName()) + 12.0);
            button.setBounds(width, 17).setPosition(MARGIN + index * 47.0, 73).setRadius(4)
                    .setClickable(!PersonalFmManager.isLoading())
                    .setColor(selected ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : (button.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
            button.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0 || PersonalFmManager.isLoading()) return mouseButton == 0;
            PersonalFmManager.selectMode(mode, mode.getDefaultSubMode());
            return true;
        });
    }

    private void addSceneButton(String name, String subMode, int index) {
        RoundedButtonWidget button = new RoundedButtonWidget(name, FontManager.pf12bold);
        addChild(button);
        button.setShouldOverrideMouseCursor(true);
        button.setBeforeRenderCallback(() -> {
            boolean selected = subMode.equals(PersonalFmManager.getSelectedSubMode());
            button.setBounds(38, 16).setPosition(MARGIN + index * 43.0, 94).setRadius(4)
                    .setClickable(!PersonalFmManager.isLoading())
                    .setColor(selected ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : (button.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
            button.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0 || PersonalFmManager.isLoading()) return mouseButton == 0;
            PersonalFmManager.selectMode(PersonalFmManager.Mode.SCENE_RCMD, subMode);
            return true;
        });
    }
}
