package tritium.rendering.ui.widgets;

import lombok.Getter;
import tritium.management.FontManager;
import tritium.rendering.entities.impl.ScrollText;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.ui.AbstractWidget;
import tritium.utils.Lazy;

import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/7/8 20:59
 */
public class LabelWidget extends AbstractWidget<LabelWidget> {

    Supplier<String> label = () -> "点击输入文字";
    @Getter
    CFontRenderer font = FontManager.pf18;

    @Getter
    double maxWidth = -1;

    @Getter
    private WidthLimitType widthLimitType = WidthLimitType.SCROLL;

    public enum WidthLimitType {
        SCROLL,
        TRIM_TO_WIDTH
    }

    Lazy<ScrollText> scrollText = Lazy.of(ScrollText::new);

    public LabelWidget(String label, CFontRenderer font) {
        this.setLabel(label);
        this.setFont(font);
    }

    public LabelWidget(Supplier<String> label, CFontRenderer font) {
        this.setLabel(label);
        this.setFont(font);
    }

    public LabelWidget(String label) {
        this.setLabel(label);
    }

    public LabelWidget(Supplier<String> label) {
        this.setLabel(label);
    }

    public LabelWidget() {

    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        boolean widthNotLimited = this.getMaxWidth() == -1;

        String lbl = this.getLabel();

        if (widthNotLimited)
            font.drawString(lbl, this.getX(), this.getY(), this.getHexColor());
        else {

            if (this.widthLimitType == WidthLimitType.SCROLL) {
                this.scrollText.getValue().render(font, lbl, this.getX(), this.getY(), this.getMaxWidth(), this.getHexColor());
            } else {
                font.drawString(font.trim(lbl, this.getMaxWidth()), this.getX(), this.getY(), this.getHexColor());
            }

        }

        double width;
        double stringWidth = font.getStringWidthD(lbl);
        width = widthNotLimited ? stringWidth : Math.min(this.getMaxWidth(), stringWidth);
        this.setBounds(width, font.getStringHeight(lbl));
    }

    public LabelWidget setMaxWidth(double maxWidth) {
        this.maxWidth = maxWidth;
        return this;
    }

    public LabelWidget setWidthLimitType(WidthLimitType widthLimitType) {
        this.widthLimitType = widthLimitType;
        return this;
    }

    public LabelWidget setFont(CFontRenderer font) {
        this.font = font;
        return this;
    }

    public String getLabel() {
        String lbl = label.get();
        return lbl == null ? "null" : lbl;
    }

    public LabelWidget setLabel(String label) {
        this.setLabel(() -> label);
        return this;
    }

    public LabelWidget setLabel(Supplier<String> label) {
        this.label = label;
        return this;
    }
}
