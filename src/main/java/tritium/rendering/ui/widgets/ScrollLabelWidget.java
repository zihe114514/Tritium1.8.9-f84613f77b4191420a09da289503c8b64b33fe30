package tritium.rendering.ui.widgets;

import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Interpolations;
import tritium.utils.timing.Timer;

import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/7/9 16:58
 */
public class ScrollLabelWidget extends LabelWidget {

    Timer t = new Timer();
    double scrollOffset = 0;

    long waitTime = 3000;

    @Override
    public void onRender(double mouseX, double mouseY) {

        double x = this.getX();
        double y = this.getY();
        double width = this.getWidth();
        String label = this.getLabel();

        StencilClipManager.beginClip(() -> {
            double exp = 2;
            Rect.draw(x, y - exp, width,  font.getHeight() + exp * 2, -1);
        });

        font.drawString(label, x + this.scrollOffset, y, this.getHexColor());
        float stringWidth = font.getWidth(label);

        if (stringWidth > width) {

            String spacing = "    ";
            double dest = -(stringWidth + font.getWidth(spacing));

            if (t.isDelayed(waitTime)) {
                scrollOffset = Interpolations.interpolate(scrollOffset, dest, .5f);
            }

            font.drawString(spacing + label, x + stringWidth + scrollOffset, y, this.getHexColor());

            if (Math.abs(dest - scrollOffset) == 0) {
                scrollOffset = 0;
                t.reset();
            }

        } else {
        	this.scrollOffset = 0d;
        }

        StencilClipManager.endClip();

        this.setHeight(font.getHeight());
    }

    /**
     * 设置滚动一次之后的等待时间, 单位: 毫秒
     */
    public ScrollLabelWidget setWaitTime(long waitTime) {
        this.waitTime = waitTime;
        return this;
    }

    public ScrollLabelWidget setLabel(Supplier<String> label) {
        String lbl = this.getLabel();
        super.setLabel(label);

        if (!lbl.equals(label.get())) {
            // 重置滚动
            this.scrollOffset = 0;
        }
        return this;
    }
}
