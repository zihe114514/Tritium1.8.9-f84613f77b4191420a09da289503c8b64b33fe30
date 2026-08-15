package tritium.rendering.ui.widgets;

import tritium.rendering.font.CFontRenderer;

import java.awt.*;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/10/17 19:05
 */
public class RoundedButtonWidget extends RoundedRectWidget {

    LabelWidget lw;

    public RoundedButtonWidget(Supplier<String> label, CFontRenderer fr) {
        lw = new LabelWidget(label, fr);

        this.addChild(lw);
        lw.setClickable(false);

        lw.setBeforeRenderCallback(() -> lw.center());

        this.setShouldOverrideMouseCursor(true);
    }

    public RoundedButtonWidget(String label, CFontRenderer fr) {
        this(() -> label, fr);
    }

    public RoundedButtonWidget setTextColor(int color) {
        lw.setColor(color);
        return this;
    }

    public RoundedButtonWidget setTextColor(Color color) {
        lw.setColor(color);
        return this;
    }

}
