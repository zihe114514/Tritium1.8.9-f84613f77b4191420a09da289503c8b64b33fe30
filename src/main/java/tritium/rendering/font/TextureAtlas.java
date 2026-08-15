package tritium.rendering.font;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import tritium.interfaces.SharedConstants;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class TextureAtlas implements SharedConstants {

    private static final int ATLAS_SIZE = 2048;
    private static final int PADDING = 2;

    private int textureId;
    private int currentX = PADDING;
    private int currentY = PADDING;
    private int currentRowHeight = 0;

    private final List<AtlasRegion> regions = new ArrayList<>();

    public TextureAtlas() {
        this.init();
    }

    public void init() {
        MultiThreadingUtil.runOnMainThread(() -> {
            this.textureId = api.getGLStateManager().generateTexture();
            api.getGLStateManager().bindTexture(textureId);

            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0.0F);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, (float) 0);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0.0F);

            RenderSystem.linearFilter();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_ALPHA,
                    ATLAS_SIZE, ATLAS_SIZE, 0,
                    GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

            api.getGLStateManager().bindTexture(0);
        });
    }

    public AtlasRegion upload(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        if (currentX + width + PADDING > ATLAS_SIZE) {
            currentX = PADDING;
            currentY += currentRowHeight + PADDING;
            currentRowHeight = 0;
        }

        if (currentY + height + PADDING > ATLAS_SIZE) {
            return null;
        }

        ByteBuffer buffer = imageToBuffer(image);

        api.getGLStateManager().bindTexture(textureId);
        int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0,
                currentX, currentY,
                width, height,
                GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
        api.getGLStateManager().bindTexture(0);

        float u0 = (float) currentX / ATLAS_SIZE;
        float v0 = (float) currentY / ATLAS_SIZE;
        float u1 = (float) (currentX + width) / ATLAS_SIZE;
        float v1 = (float) (currentY + height) / ATLAS_SIZE;

        AtlasRegion region = new AtlasRegion(u0, v0, u1, v1, width, height);
        regions.add(region);

        currentX += width + PADDING;
        currentRowHeight = Math.max(currentRowHeight, height);

        return region;
    }

    private ByteBuffer imageToBuffer(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                int alpha = (pixel >> 24) & 0xFF;
                buffer.put((byte) alpha);
            }
        }

        buffer.flip();
        return buffer;
    }

    public int getTextureId() {
        return textureId;
    }

    public void destroy() {
        api.getGLStateManager().deleteTexture(textureId);
    }

    public static class AtlasRegion {
        public final float u0, v0, u1, v1;
        public final int width, height;

        public AtlasRegion(float u0, float v0, float u1, float v1, int width, int height) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.width = width;
            this.height = height;
        }
    }
}