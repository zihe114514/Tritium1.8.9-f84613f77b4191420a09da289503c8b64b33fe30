package com.muoniumplayer.core.rendering.texture;

import lombok.experimental.UtilityClass;
import com.muoniumplayer.core.MuoniumPlayerEventHandler;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Optional;

/**
 * @author IzumiiKonata
 * @since 11/25/2023
 */
@UtilityClass
public class Textures implements SharedConstants {

    public Optional<ITextureObject> getTextureOrLoadAsynchronously(Location location, BufferedImage img) {

        TextureManager textureManager = TextureManager.getInstance();

        ITextureObject texture = textureManager.getTexture(location);

        if (texture == null) {
            Textures.loadTextureAsyncly(location, img);
        }

        return Optional.ofNullable(texture);
    }

    public void downloadTextureAndLoadAsync(String url, Location location) {
        MultiThreadingUtil.runAsync(() -> {
            try (InputStream inputStream = HttpUtils.downloadStream(url)) {
                if (inputStream != null) {
                    BufferedImage img = DynamicTexture.readImage(inputStream);

                    if (img != null) {
                        Textures.loadTexture(location, img);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void loadTexture(Location location, BufferedImage img) {
        Textures.loadTexture(location, img, true, false);
    }

    public void loadTexture(Location location, BufferedImage img, boolean clearable, boolean linear) {

        if (img == null)
            return;

        if (!MuoniumPlayerExtension.isCallingFromMainThread()) {
            MuoniumPlayerEventHandler.addScheduledTask(() -> loadTexture(location, img, clearable, linear));
            return;
        }

        DynamicTexture dynamicTexture = new DynamicTexture(img, clearable, linear);
        TextureManager.getInstance().loadTexture(location, dynamicTexture);
    }

    public void loadTextureAsyncly(Location location, BufferedImage img, boolean flush, boolean clearable, boolean linear, Runnable after) {

        MultiThreadingUtil.runAsync(
                () -> {
                    Textures.loadTexture(location, img, clearable, linear);

                    if (flush) {
                        img.flush();
                    }

                    if (after != null) {
                        after.run();
                    }
                }
        );

    }

    public void loadTextureAsyncly(Location location, BufferedImage img, boolean flush, boolean clearable, boolean linear) {
        Textures.loadTextureAsyncly(location, img, flush, clearable, linear, null);
    }

    public void loadTextureAsyncly(Location location, BufferedImage img, boolean flush) {
        Textures.loadTextureAsyncly(location, img, flush, true, false, null);
    }

    public void loadTextureAsyncly(Location location, BufferedImage img, Runnable after) {
        Textures.loadTextureAsyncly(location, img, true, true, false, after);
    }

    public void loadTextureAsyncly(Location location, BufferedImage img) {
        Textures.loadTextureAsyncly(location, img, true, true, false, null);
    }

    public void triggerLoad(Location location, ITextureObject object) {
        TextureManager.getInstance().loadTexture(location, object);
    }

}
