package tritium.rendering.ui.widgets;

import lombok.Getter;
import lombok.Setter;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.texture.ITextureObject;
import tritium.rendering.ui.AbstractWidget;
import tritium.utils.Location;

import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/9/30 15:11
 */
public class RoundedImageWidget extends AbstractWidget<RoundedImageWidget> {

    @Getter
    @Setter
    private Supplier<Location> locImg;

    @Getter
    private double radius = 0;

    boolean fadeIn = false;

    @Getter
    boolean linearFilter = false;

    public RoundedImageWidget(Supplier<Location> locImg, double x, double y, double width, double height) {
        this.setBounds(x, y, width, height);
        this.locImg = locImg;
    }

    public RoundedImageWidget(Location locImg, double x, double y, double width, double height) {
        this(() -> locImg, x, y, width, height);
    }

    public RoundedImageWidget(double x, double y, double width, double height) {
        this(() -> null, x, y, width, height);
    }

    public RoundedImageWidget fadeIn() {
        fadeIn = true;
        this.setAlpha(0);
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        Location img = locImg.get();

        if (img == null)
            return;

        ITextureObject textureObject = TextureManager.getInstance().getTexture(img);

        if (textureObject == null)
            return;

        if (fadeIn)
            this.setAlpha(Interpolations.interpolate(this.getWidgetAlpha(), 1.0f, 0.15f));

        api.getGLStateManager().color(1, 1, 1, this.getAlpha());
        api.getGLStateManager().bindTexture(textureObject.getGlTextureId());

        if (this.isLinearFilter())
            textureObject.linearFilter();
        else
            textureObject.nearestFilter();

        this.roundedRectTextured(this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.getRadius(), this.getAlpha());
    }

    public RoundedImageWidget setRadius(double radius) {
        this.radius = radius;
        return this;
    }

    public RoundedImageWidget setLinearFilter(boolean linearFilter) {
        this.linearFilter = linearFilter;
        return this;
    }
}
