package tritium.ncm.music;

import lombok.Cleanup;
import tritium.ncm.music.dto.Music;
import org.apache.commons.io.IOUtils;
import tritium.rendering.GaussianKernel;
import tritium.rendering.TextureManager;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.texture.Textures;
import tritium.utils.Location;
import tritium.utils.network.HttpUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Asynchronous music-cover retrieval, texture upload and lyric-background blur generation.
 *
 * <p>This service intentionally preserves the existing asynchronous task boundaries: the main
 * cover texture is uploaded as soon as it downloads, while the blurred lyric texture is produced
 * on a separate task.</p>
 */
final class MusicCoverService {

    private static final Kernel GAUSSIAN_KERNEL = new Kernel(41, 41, GaussianKernel.generate(41));

    private MusicCoverService() {
    }

    static void loadMusicCover(Music music, boolean forceReload) {
        Location musicCover = music.getCoverLocation();
        Location musicCoverSmall = music.getSmallCoverLocation();
        Location musicCoverBlur = music.getBlurredCoverLocation();
        TextureManager textureManager = TextureManager.getInstance();

        if (shouldLoadCover(textureManager, musicCover, forceReload)) {
            loadMainCoverAsync(music, musicCover, musicCoverBlur);
        }
        if (shouldLoadCover(textureManager, musicCoverSmall, forceReload)) {
            loadSmallCoverAsync(music, musicCoverSmall);
        }
    }

    static BufferedImage gaussianBlur(BufferedImage image, int blur) {
        Map<RenderingHints.Key, Object> map = new HashMap<>();
        map.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        map.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        map.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        RenderingHints hints = new RenderingHints(map);

        ConvolveOp operation = new ConvolveOp(GAUSSIAN_KERNEL, ConvolveOp.EDGE_NO_OP, hints);
        BufferedImage filtered = operation.filter(image, null);
        BufferedImage output = new BufferedImage(filtered.getWidth(), filtered.getHeight(), filtered.getType());
        Graphics2D graphics = (Graphics2D) output.getGraphics();
        graphics.setRenderingHints(map);
        graphics.drawImage(filtered, -blur, -blur, filtered.getWidth() + blur * 2,
                filtered.getHeight() + blur * 2, null);
        return output;
    }

    private static boolean shouldLoadCover(TextureManager textureManager, Location coverLocation,
                                           boolean forceReload) {
        return textureManager.getTexture(coverLocation) == null || forceReload;
    }

    private static void loadMainCoverAsync(Music music, Location musicCover, Location musicCoverBlur) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                @Cleanup
                InputStream coverStream = HttpUtils.downloadStream(music.getCoverUrl(320), 5);
                byte[] imageData = IOUtils.toByteArray(coverStream);
                BufferedImage coverImage = DynamicTexture.readImage(new ByteArrayInputStream(imageData));
                if (coverImage != null) {
                    loadCoverTextures(coverImage, musicCover, musicCoverBlur);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private static void loadCoverTextures(BufferedImage coverImage, Location musicCover, Location musicCoverBlur) {
        Textures.loadTexture(musicCover, coverImage);
        MultiThreadingUtil.runAsync(() -> {
            BufferedImage inputImage = new BufferedImage(coverImage.getWidth(), coverImage.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            inputImage.setRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(),
                    coverImage.getRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(), null, 0,
                            coverImage.getWidth()), 0, coverImage.getWidth());
            Textures.loadTexture(musicCoverBlur, gaussianBlur(inputImage, 31));
        });
    }

    private static void loadSmallCoverAsync(Music music, Location musicCoverSmall) {
        MultiThreadingUtil.runAsync(() -> {
            InputStream smallCoverStream = HttpUtils.downloadStream(music.getCoverUrl(128), 5);
            BufferedImage smallCoverImage = DynamicTexture.readImage(smallCoverStream);
            Textures.loadTexture(musicCoverSmall, smallCoverImage);
        });
    }
}
