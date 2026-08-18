package com.muoniumplayer.core.rendering.ui.widgets;

import lombok.Getter;
import lombok.Setter;
import com.muoniumplayer.core.rendering.Image;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.utils.Location;

import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/9/30 15:11
 */
public class ImageWidget extends AbstractWidget<ImageWidget> {

    @Getter
    @Setter
    private Supplier<Location> locImg;

    public ImageWidget(Supplier<Location> locImg, double x, double y, double width, double height) {
        this.setBounds(x, y, width, height);
        this.locImg = locImg;
    }

    public ImageWidget(Location locImg, double x, double y, double width, double height) {
        this(() -> locImg, x, y, width, height);
    }

    public ImageWidget(double x, double y, double width, double height) {
        this(() -> null, x, y, width, height);
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        Location img = locImg.get();

        if (img == null)
            return;

        ITextureObject textureObject = TextureManager.getInstance().getTexture(img);

        if (textureObject == null)
            return;

        api.getGLStateManager().color(1, 1, 1, this.getAlpha());
        Image.draw(textureObject, this.getX(), this.getY(), this.getWidth(), this.getHeight(), Image.Type.NoColor);
    }
}
