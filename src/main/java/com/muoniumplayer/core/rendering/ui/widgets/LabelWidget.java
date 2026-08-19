package com.muoniumplayer.core.rendering.ui.widgets;

import lombok.Getter;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.entities.impl.ScrollText;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.utils.Lazy;

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
        CFontRenderer activeFont = this.font != null ? this.font : FontManager.pf18;

        // Labels are used extensively with asynchronous/state-backed Suppliers.
        // A provider may temporarily return null while a page is being rebuilt, and
        // a renderer can also be unavailable during the very first initialization
        // frame. Never let either transient state abort the whole NCMScreen tree.
        if (activeFont == null) {
            this.setBounds(0, 0);
            return;
        }

        if (widthNotLimited) {
            activeFont.drawString(lbl, this.getX(), this.getY(), this.getHexColor());
        } else if (this.widthLimitType == WidthLimitType.SCROLL) {
            this.scrollText.getValue().render(activeFont, lbl, this.getX(), this.getY(),
                    this.getMaxWidth(), this.getHexColor());
        } else {
            activeFont.drawString(activeFont.trim(lbl, this.getMaxWidth()), this.getX(), this.getY(), this.getHexColor());
        }

        double stringWidth = activeFont.getStringWidthD(lbl);
        double width = widthNotLimited ? stringWidth : Math.min(this.getMaxWidth(), stringWidth);
        this.setBounds(Math.max(0, width), Math.max(0, activeFont.getStringHeight(lbl)));
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
        // Keep a null value recoverable: FontManager.pf18 may not be ready when a
        // widget is constructed, so onRender will resolve the fallback later.
        this.font = font;
        return this;
    }

    public String getLabel() {
        Supplier<String> supplier = this.label;
        if (supplier == null) {
            return "";
        }
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            // A transient provider failure must not crash rendering of every panel.
            return "";
        }
    }

    public LabelWidget setLabel(String label) {
        this.setLabel(() -> label == null ? "" : label);
        return this;
    }

    public LabelWidget setLabel(Supplier<String> label) {
        this.label = label == null ? () -> "" : label;
        return this;
    }
}
