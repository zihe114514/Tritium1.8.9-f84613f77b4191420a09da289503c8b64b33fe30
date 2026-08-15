package tritium.utils;

import lombok.experimental.UtilityClass;
import today.opai.api.features.ExtensionModule;
import today.opai.api.features.ExtensionWidget;

/**
 * @author IzumiiKonata
 * Date: 2026/4/1 10:21
 */
@UtilityClass
public class WidgetWrapper {

    public Tuple<ExtensionWidget, WidgetPosSizeInterface> createWrapper(ExtensionModule module, Runnable render) {
        ExtensionWidget widget = new ExtensionWidget(module.getName() + " Widget") {
            @Override
            public void render() {
                render.run();
            }

            @Override
            public boolean renderPredicate() {
                return module.isEnabled();
            }
        };

        WidgetPosSizeInterface posInterface = new WidgetPosSizeInterface() {
            @Override
            public float getX() {
                return widget.getX();
            }

            @Override
            public float getY() {
                return widget.getY();
            }

            @Override
            public void setX(float x) {
                widget.setX(x);
            }

            @Override
            public void setY(float y) {
                widget.setY(y);
            }

            @Override
            public float getWidth() {
                return widget.getWidth();
            }

            @Override
            public float getHeight() {
                return widget.getHeight();
            }

            @Override
            public void setWidth(float width) {
                widget.setWidth(width);
            }

            @Override
            public void setHeight(float height) {
                widget.setHeight(height);
            }
        };

        return Tuple.of(widget, posInterface);
    }

    public interface WidgetPosSizeInterface {

        float getX();
        float getY();
        void setX(float x);
        void setY(float y);

        float getWidth();
        float getHeight();
        void setWidth(float width);
        void setHeight(float height);

    }

}
