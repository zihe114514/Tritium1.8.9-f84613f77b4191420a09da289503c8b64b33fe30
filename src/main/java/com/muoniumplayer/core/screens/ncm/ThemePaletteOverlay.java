package com.muoniumplayer.core.screens.ncm;

import org.lwjgl.input.Mouse;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;

import java.awt.Color;

/**
 * 播放器主题调色板：在主题按钮上右键打开的二级菜单。
 *
 * <p>标签直接取自 {@link NCMScreen.ColorType}，也就是播放器每次换主题时真正会变的那十个颜色配置项，
 * 因此调色板里的每一行都对得上主题预设的一列。改动通过
 * {@link NCMTheme#setCustomColor(NCMScreen.ColorType, int, boolean)} 写入：拖动过程中只改内存所以能实时
 * 预览，松手才落盘；覆盖值按主题各存一份，换主题不会互相污染，删掉覆盖即回到预设色。</p>
 *
 * @author Codex
 */
public final class ThemePaletteOverlay extends NCMPanel {

    private static final double DIALOG_WIDTH = 468.0;
    private static final double DIALOG_HEIGHT = 356.0;
    private static final double CELL_HEIGHT = 30.0;

    private boolean closing;
    private double presentation;

    private NCMScreen.ColorType editing = NCMScreen.ColorType.ACCENT;
    private float hue;
    private float saturation;
    private float brightness;

    @Override
    public void onInit() {
        loadEditingColor();
        build();
    }

    public boolean shouldClose() {
        return this.closing;
    }

    public void dispose() {
        this.closing = true;
    }

    public void handleEscape() {
        dispose();
    }

    private void build() {
        getChildren().clear();

        Panel dialog = createDialog();
        addTitle(dialog, "主题调色板",
                "实时预览 · 松手即保存 · 每套主题各自记住自己的调色");

        NCMScreen.ColorType[] types = NCMScreen.ColorType.values();
        int rows = (types.length + 1) / 2;
        for (int index = 0; index < types.length; index++) {
            double cellX = 16.0 + (index / rows) * 220.0;
            double cellY = 66.0 + (index % rows) * CELL_HEIGHT;
            addColorCell(dialog, types[index], cellX, cellY);
        }

        double sliderTop = 66.0 + rows * CELL_HEIGHT + 10.0;
        addEditorLabel(dialog, sliderTop);
        addSlider(dialog, PaletteChannel.HUE, sliderTop + 20.0);
        addSlider(dialog, PaletteChannel.SATURATION, sliderTop + 46.0);
        addSlider(dialog, PaletteChannel.BRIGHTNESS, sliderTop + 72.0);

        addFooter(dialog);
    }

    private void addColorCell(Panel dialog, NCMScreen.ColorType type, double x, double y) {
        RoundedRectWidget cell = new RoundedRectWidget();
        dialog.addChild(cell);
        cell.setRadius(6.0);
        cell.setShouldOverrideMouseCursor(true);
        cell.setBeforeRenderCallback(() -> {
            cell.setBounds(212.0, CELL_HEIGHT - 4.0).setPosition(x, y);
            boolean selected = this.editing == type;
            cell.setColor(selected
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : (cell.isHovering()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)
                            : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND)));
        });
        cell.setOnClickCallback((relativeX, relativeY, button) -> {
            if (button == 0) {
                this.editing = type;
                loadEditingColor();
            } else if (button == 1) {
                // 右键单项直接撤销这一项的调色，回到预设色。
                NCMTheme.resetCustomColor(type);
                if (this.editing == type) loadEditingColor();
            }
            return true;
        });

        LabelWidget label = new LabelWidget(type.getDisplayName(), FontManager.pf12bold);
        cell.addChild(label);
        label.setClickable(false);
        label.setBeforeRenderCallback(() -> label
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(8.0, (CELL_HEIGHT - 4.0 - label.getHeight()) * .5));

        LabelWidget hex = new LabelWidget(() -> hexText(NCMTheme.getTargetColor(type))
                + (NCMTheme.hasCustomColor(type) ? " ·" : ""), FontManager.pf10bold);
        cell.addChild(hex);
        hex.setClickable(false);
        hex.setBeforeRenderCallback(() -> hex
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(212.0 - 44.0 - hex.getWidth() - 6.0,
                        (CELL_HEIGHT - 4.0 - hex.getHeight()) * .5));

        SwatchWidget swatch = new SwatchWidget(type);
        cell.addChild(swatch);
        swatch.setClickable(false);
        swatch.setBeforeRenderCallback(() -> swatch
                .setBounds(38.0, 16.0)
                .setPosition(212.0 - 38.0 - 6.0, (CELL_HEIGHT - 4.0 - 16.0) * .5));
    }

    private void addEditorLabel(Panel dialog, double y) {
        LabelWidget label = new LabelWidget(
                () -> "正在调整：" + this.editing.getDisplayName() + " · " + hexText(currentEditingRgb()),
                FontManager.pf12bold);
        dialog.addChild(label);
        label.setClickable(false);
        label.setBeforeRenderCallback(() -> label
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(16.0, y));

        LabelWidget hint = new LabelWidget("右键色块可撤销该项", FontManager.pf10bold);
        dialog.addChild(hint);
        hint.setClickable(false);
        hint.setBeforeRenderCallback(() -> hint
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(Math.max(16.0, dialog.getWidth() - 16.0 - hint.getWidth()), y + 1.0));
    }

    private void addSlider(Panel dialog, PaletteChannel channel, double y) {
        LabelWidget label = new LabelWidget(channel.label, FontManager.pf10bold);
        dialog.addChild(label);
        label.setClickable(false);
        label.setBeforeRenderCallback(() -> label
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(16.0, y + 5.0));

        SliderWidget slider = new SliderWidget(channel);
        dialog.addChild(slider);
        slider.setBeforeRenderCallback(() -> slider
                .setBounds(Math.max(40.0, dialog.getWidth() - 16.0 - 46.0 - 40.0), 16.0)
                .setPosition(46.0, y));

        LabelWidget value = new LabelWidget(() -> channel.format(this), FontManager.pf10bold);
        dialog.addChild(value);
        value.setClickable(false);
        value.setBeforeRenderCallback(() -> value
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(Math.max(16.0, dialog.getWidth() - 16.0 - value.getWidth()), y + 5.0));
    }

    private void addFooter(Panel dialog) {
        RoundedButtonWidget resetOne = new RoundedButtonWidget("撤销此项", FontManager.pf12bold);
        dialog.addChild(resetOne);
        resetOne.setRadius(7.0);
        resetOne.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMTheme.resetCustomColor(this.editing);
            loadEditingColor();
            return true;
        });
        resetOne.setBeforeRenderCallback(() -> {
            resetOne.setBounds(88.0, 26.0).setPosition(16.0, dialog.getHeight() - 38.0);
            resetOne.setColor(resetOne.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            resetOne.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        RoundedButtonWidget resetAll = new RoundedButtonWidget("恢复主题原色", FontManager.pf12bold);
        dialog.addChild(resetAll);
        resetAll.setRadius(7.0);
        resetAll.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMTheme.resetAllCustomColors();
            loadEditingColor();
            return true;
        });
        resetAll.setBeforeRenderCallback(() -> {
            resetAll.setBounds(112.0, 26.0).setPosition(112.0, dialog.getHeight() - 38.0);
            resetAll.setColor(resetAll.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            resetAll.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        RoundedButtonWidget done = new RoundedButtonWidget("完成", FontManager.pf12bold);
        dialog.addChild(done);
        done.setRadius(7.0);
        done.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            dispose();
            return true;
        });
        done.setBeforeRenderCallback(() -> {
            done.setBounds(84.0, 26.0)
                    .setPosition(Math.max(16.0, dialog.getWidth() - 100.0), dialog.getHeight() - 38.0);
            done.setColor(done.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            done.setTextColor(0xFFFFFF);
        });

        LabelWidget theme = new LabelWidget(() -> "当前主题：" + NCMTheme.getCurrentName(), FontManager.pf10bold);
        dialog.addChild(theme);
        theme.setClickable(false);
        theme.setBeforeRenderCallback(() -> theme
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(232.0, dialog.getHeight() - 30.0));
    }

    private Panel createDialog() {
        this.presentation = 0.0;
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
            this.presentation = Interpolations.interpolate(this.presentation, 1.0, .18f);
            double width = Math.max(1.0, Math.min(DIALOG_WIDTH, getWidth() - 24.0));
            double height = Math.max(1.0, Math.min(DIALOG_HEIGHT, getHeight() - 24.0));
            dialog.setBounds(width, height);
            dialog.setAlpha((float) this.presentation);
            dialog.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - height * .5 + (1.0 - this.presentation) * 10.0);
        });

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(13.0);
        background.setBeforeRenderCallback(() -> background
                .setMargin(0)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND)));
        return dialog;
    }

    private void addTitle(Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(16.0, 14.0));

        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1.0, dialog.getWidth() - 32.0));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(16.0, 39.0);
        });
    }

    /** 把当前编辑目标的颜色读进 HSB 三个通道。 */
    private void loadEditingColor() {
        int rgb = NCMTheme.getTargetColor(this.editing);
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        this.hue = hsb[0];
        this.saturation = hsb[1];
        this.brightness = hsb[2];
    }

    private int currentEditingRgb() {
        return Color.HSBtoRGB(this.hue, this.saturation, this.brightness) & 0xFFFFFF;
    }

    /** @param persist true 表示这是一次落盘（松手），false 表示仅实时预览。 */
    private void applyEditingColor(boolean persist) {
        NCMTheme.setCustomColor(this.editing, currentEditingRgb(), persist);
    }

    private static String hexText(int rgb) {
        return String.format("#%06X", Integer.valueOf(rgb & 0xFFFFFF));
    }

    private enum PaletteChannel {
        HUE("色相"),
        SATURATION("饱和"),
        BRIGHTNESS("明度");

        private final String label;

        PaletteChannel(String label) {
            this.label = label;
        }

        private float read(ThemePaletteOverlay overlay) {
            switch (this) {
                case HUE: return overlay.hue;
                case SATURATION: return overlay.saturation;
                default: return overlay.brightness;
            }
        }

        private void write(ThemePaletteOverlay overlay, float value) {
            float clamped = Math.max(0f, Math.min(1f, value));
            switch (this) {
                case HUE: overlay.hue = clamped; break;
                case SATURATION: overlay.saturation = clamped; break;
                default: overlay.brightness = clamped; break;
            }
        }

        private String format(ThemePaletteOverlay overlay) {
            return this == HUE
                    ? Math.round(read(overlay) * 360f) + "°"
                    : Math.round(read(overlay) * 100f) + "%";
        }
    }

    /** 单项颜色的预览色块：直接读主题的动画色，所以能跟着过渡一起变。 */
    private final class SwatchWidget extends AbstractWidget<SwatchWidget> {

        private final NCMScreen.ColorType type;

        private SwatchWidget(NCMScreen.ColorType type) {
            this.type = type;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float widgetAlpha = this.getAlpha();
            int rgb = NCMScreen.getColor(this.type);
            this.roundedRect(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 4.0,
                    argb(rgb, widgetAlpha));
            this.roundedOutline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 4.0, .7,
                    new Color(255, 255, 255, alpha255(widgetAlpha * .28f)));
        }
    }

    /** 一条 HSB 通道滑块：轨道用渐变段拼出通道本身的取值范围，拖动即时预览。 */
    private final class SliderWidget extends AbstractWidget<SliderWidget> {

        private static final int HUE_SEGMENTS = 24;

        private final PaletteChannel channel;
        private boolean dragging;

        private SliderWidget(PaletteChannel channel) {
            this.channel = channel;
            this.setShouldOverrideMouseCursor(true);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            if (this.dragging) {
                if (Mouse.isButtonDown(0)) {
                    applyFromMouse(mouseX);
                } else {
                    // 松手才写盘：拖动途中只改内存，避免每帧写一次配置文件。
                    this.dragging = false;
                    ThemePaletteOverlay.this.applyEditingColor(true);
                }
            }

            float widgetAlpha = this.getAlpha();
            double trackY = this.getY() + this.getHeight() * .5 - 3.0;
            double trackHeight = 6.0;
            if (this.channel == PaletteChannel.HUE) {
                double segmentWidth = this.getWidth() / HUE_SEGMENTS;
                for (int segment = 0; segment < HUE_SEGMENTS; segment++) {
                    float from = segment / (float) HUE_SEGMENTS;
                    float to = (segment + 1) / (float) HUE_SEGMENTS;
                    this.roundedRectGradientHorizontal(this.getX() + segment * segmentWidth, trackY,
                            segmentWidth + .5, trackHeight, segment == 0 || segment == HUE_SEGMENTS - 1 ? 3.0 : .0,
                            color(Color.HSBtoRGB(from, 1f, 1f), widgetAlpha),
                            color(Color.HSBtoRGB(to, 1f, 1f), widgetAlpha));
                }
            } else {
                int start = this.channel == PaletteChannel.SATURATION
                        ? Color.HSBtoRGB(ThemePaletteOverlay.this.hue, 0f, ThemePaletteOverlay.this.brightness)
                        : 0x000000;
                int end = this.channel == PaletteChannel.SATURATION
                        ? Color.HSBtoRGB(ThemePaletteOverlay.this.hue, 1f, ThemePaletteOverlay.this.brightness)
                        : Color.HSBtoRGB(ThemePaletteOverlay.this.hue, ThemePaletteOverlay.this.saturation, 1f);
                this.roundedRectGradientHorizontal(this.getX(), trackY, this.getWidth(), trackHeight, 3.0,
                        color(start, widgetAlpha), color(end, widgetAlpha));
            }

            double knobX = this.getX() + this.getWidth() * this.channel.read(ThemePaletteOverlay.this);
            double knobSize = this.getHeight();
            this.roundedRect(knobX - knobSize * .5, this.getY(), knobSize, knobSize, knobSize * .5,
                    argb(0xFFFFFF, widgetAlpha * .96f));
            this.roundedRect(knobX - knobSize * .5 + 2.0, this.getY() + 2.0,
                    knobSize - 4.0, knobSize - 4.0, (knobSize - 4.0) * .5,
                    argb(ThemePaletteOverlay.this.currentEditingRgb(), widgetAlpha));
        }

        @Override
        public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
            if (mouseButton != 0) return false;
            this.dragging = true;
            applyFromMouse(this.getX() + relativeX);
            return true;
        }

        private void applyFromMouse(double mouseX) {
            double progress = (mouseX - this.getX()) / Math.max(1.0, this.getWidth());
            this.channel.write(ThemePaletteOverlay.this, (float) progress);
            ThemePaletteOverlay.this.applyEditingColor(false);
        }
    }

    private static int alpha255(float alpha) {
        return Math.max(0, Math.min(255, Math.round(alpha * 255f)));
    }

    private static int argb(int rgb, float alpha) {
        return (alpha255(alpha) << 24) | (rgb & 0xFFFFFF);
    }

    private static Color color(int rgb, float alpha) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha255(alpha));
    }
}
