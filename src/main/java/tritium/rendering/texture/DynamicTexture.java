package tritium.rendering.texture;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import tritium.TritiumMusicExtension;
import tritium.rendering.TextureUtil;
import tritium.utils.Location;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

@Getter
public class DynamicTexture extends AbstractTexture {
    public int[] dynamicTextureData;

    /**
     * width of this icon in pixels
     */
    protected int width;

    /**
     * height of this icon in pixels
     */
    protected int height;

    @Getter
    @Setter
    protected boolean clearable = true;

    public DynamicTexture(Location img) {
        this(readImage(img.getResourceStream()));
    }

    public DynamicTexture(BufferedImage bufferedImage) {
        this(bufferedImage.getWidth(), bufferedImage.getHeight(), bufferedImage.getType());
        bufferedImage.getRGB(0, 0, bufferedImage.getWidth(), bufferedImage.getHeight(), this.dynamicTextureData, 0, bufferedImage.getWidth());
        this.updateDynamicTexture();
    }

    public DynamicTexture(BufferedImage bufferedImage, boolean clearable) {
        this(bufferedImage.getWidth(), bufferedImage.getHeight(), bufferedImage.getType());
        bufferedImage.getRGB(0, 0, bufferedImage.getWidth(), bufferedImage.getHeight(), this.dynamicTextureData, 0, bufferedImage.getWidth());
        this.clearable = clearable;
        this.updateDynamicTexture();
    }

    public DynamicTexture(BufferedImage bufferedImage, boolean clearable, boolean linear) {
        this(bufferedImage.getWidth(), bufferedImage.getHeight(), bufferedImage.getType());
        bufferedImage.getRGB(0, 0, bufferedImage.getWidth(), bufferedImage.getHeight(), this.dynamicTextureData, 0, bufferedImage.getWidth());
        this.clearable = clearable;
        this.linear = linear;
        this.updateDynamicTexture();
    }

    public DynamicTexture(int textureWidth, int textureHeight) {
        this(textureWidth, textureHeight, BufferedImage.TYPE_INT_ARGB);
    }

    public DynamicTexture(int textureWidth, int textureHeight, int imgType) {
        this.width = textureWidth;
        this.height = textureHeight;
        this.dynamicTextureData = new int[textureWidth * textureHeight];

        this.allocateTexture(textureWidth, textureHeight);
    }

    private void extractAlphaData(BufferedImage img) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = img.getRGB(x, y);
                int alpha = (pixel >> 24) & 0xFF;
                dynamicTextureData[y * width + x] = (byte) alpha;
            }
        }
    }

    public void allocateTexture(int textureWidth, int textureHeight) {

        if (!TritiumMusicExtension.isCallingFromMainThread()) {
            MultiThreadingUtil.runOnMainThreadBlocking(() -> {
                this.allocateTextureImpl(0, textureWidth, textureHeight);
                return null;
            });
            return;
        }

        this.allocateTextureImpl(0, textureWidth, textureHeight);
    }

    public void allocateTextureImpl(int levels, int width, int height) {

        this.deleteTexture();
        this.bindTexture();

        if (levels >= 0) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0.0F);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, (float) levels);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0.0F);
        }

        for (int i = 0; i <= levels; ++i) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, i, GL11.GL_RGBA, width >> i, height >> i, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

    }

    @Getter
    @Setter
    protected boolean linear = false;

    @SneakyThrows
    public void updateDynamicTexture() {

        if (!TritiumMusicExtension.isCallingFromMainThread()) {
            MultiThreadingUtil.runOnMainThreadBlocking(() -> {
                this.updateDynamicTexture();
                return null;
            });
            return;
        }

        this.bindTexture();

        TextureUtil.setTextureBlurMipmap(isLinear(), false);
        TextureUtil.setTextureClamped(false);

        int maxBufferSize = 2097152;
        int bytesPerPixel = 4;
        int maxPixels = maxBufferSize / bytesPerPixel;
        int chunkHeight = Math.max(1, Math.min(maxPixels / width, height));

        int optimalBufferSize = width * chunkHeight * bytesPerPixel;

        ByteBuffer dataBuffer = ByteBuffer.allocateDirect(optimalBufferSize);

        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

        try {
            int[] aint = this.dynamicTextureData;

            for (int l = 0; l < height; l += chunkHeight) {
                int j = Math.min(chunkHeight, height - l);
                int k = l * width;
                int pixelCount = width * j;

                dataBuffer.clear();

                for (int i = 0; i < pixelCount; i++) {
                    int pixel = aint[k + i];
                    dataBuffer.put((byte) (pixel & 0xFF));
                    dataBuffer.put((byte) ((pixel >> 8) & 0xFF));
                    dataBuffer.put((byte) ((pixel >> 16) & 0xFF));
                    dataBuffer.put((byte) ((pixel >> 24) & 0xFF));
                }

                dataBuffer.flip();

                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, l, width, j,
                        GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, dataBuffer);
            }
        } finally {
        }

        if (this.clearable)
            this.dynamicTextureData = null;

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public void clear() {
        this.dynamicTextureData = null;
    }

    public int[] getTextureData() {
        return this.dynamicTextureData;
    }

    @SneakyThrows
    public static BufferedImage readImage(InputStream is) {
        return ImageIO.read(is);
    }
}
