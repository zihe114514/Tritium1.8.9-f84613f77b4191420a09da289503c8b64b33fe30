package tritium.rendering.ui.widgets;

import tritium.rendering.ui.AbstractWidget;

/**
 * @author IzumiiKonata
 * Date: 2025/7/8 20:28
 */
public class RoundedRectWidget extends AbstractWidget<RoundedRectWidget> {

    private double radius = 0;

    public RoundedRectWidget(double x, double y, double width, double height) {
        this.setBounds(x, y, width, height);
    }

    public RoundedRectWidget() {
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        this.roundedRect(this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.radius, this.getHexColor());
    }

    public RoundedRectWidget setRadius(double radius) {
        this.radius = radius;
        return this;
    }

    public double getRadius() {
        return radius;
    }
}
