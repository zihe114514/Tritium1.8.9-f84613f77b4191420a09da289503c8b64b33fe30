package com.muoniumplayer.core.screens.hud;

/** Immutable geometry snapshot for one HUD editor frame. */
final class HudEditorLayout {

    private HudEditorLayout() {
    }

    static Metrics calculate(int screenHeight, boolean settingsCollapsed,
                             boolean currentExpanded, boolean normalExpanded, boolean islandExpanded,
                             int settingsMargin, int collapsedSettingsHeight, int settingsHeaderHeight,
                             int sectionHeight, int colorRowHeight, int sliderRowHeight,
                             int currentSliderCount, int normalSliderCount, int islandSliderCount,
                             int pickerWidth) {
        int panelHeight = settingsCollapsed ? collapsedSettingsHeight
                : Math.max(132, screenHeight - settingsMargin - 42);
        int contentHeight = sectionHeight * 3 + 8;
        if (currentExpanded) contentHeight += colorRowHeight * 3 + currentSliderCount * sliderRowHeight;
        if (normalExpanded) contentHeight += colorRowHeight * 3 + normalSliderCount * sliderRowHeight;
        if (islandExpanded) contentHeight += colorRowHeight * 2 + islandSliderCount * sliderRowHeight;
        return new Metrics(panelHeight, contentHeight, settingsMargin, settingsHeaderHeight, pickerWidth);
    }

    static final class Metrics {
        final int panelHeight;
        final int contentHeight;
        private final int settingsMargin;
        private final int settingsHeaderHeight;
        private final int pickerWidth;

        private Metrics(int panelHeight, int contentHeight, int settingsMargin,
                        int settingsHeaderHeight, int pickerWidth) {
            this.panelHeight = panelHeight;
            this.contentHeight = contentHeight;
            this.settingsMargin = settingsMargin;
            this.settingsHeaderHeight = settingsHeaderHeight;
            this.pickerWidth = pickerWidth;
        }

        int maxScroll(int renderedPanelHeight) {
            return Math.max(0, contentHeight - (renderedPanelHeight - settingsHeaderHeight - 2));
        }

        int clampScroll(int value, int renderedPanelHeight) {
            return Math.max(0, Math.min(maxScroll(renderedPanelHeight), value));
        }

        int pickerX(int panelX) {
            return Math.max(settingsMargin, panelX - pickerWidth - 8);
        }

        int pickerY(int panelY) {
            return panelY + settingsHeaderHeight;
        }
    }
}