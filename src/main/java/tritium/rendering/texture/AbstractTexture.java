package tritium.rendering.texture;

import lombok.Getter;
import org.lwjgl.opengl.GL11;
import tritium.TritiumMusicExtension;
import tritium.interfaces.SharedConstants;
import tritium.rendering.TextureUtil;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.utils.other.multithreading.MultiThreadingUtil;

public abstract class AbstractTexture implements ITextureObject, SharedConstants {
    protected int glTextureId = -1;
    protected boolean blur;
    protected boolean mipmap;
    protected boolean blurLast;
    protected boolean mipmapLast;

    public void setBlurMipmapDirect(boolean blur, boolean mipmap) {
        this.blur = blur;
        this.mipmap = mipmap;
        int minFilter;
        int magFilter;

        if (blur) {
            minFilter = mipmap ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR;
            magFilter = GL11.GL_LINEAR;
        } else {
            minFilter = mipmap ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST;
            magFilter = GL11.GL_NEAREST;
        }

        this.bindTexture();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter);
    }

    public void setBlurMipmap(boolean blur, boolean mipmap) {

        if (blur != this.blurLast || mipmap != this.mipmapLast) {
            this.setBlurMipmapDirect(blur, mipmap);
        }

        this.blurLast = this.blur;
        this.mipmapLast = this.mipmap;
    }

    public void restoreLastBlurMipmap() {
        this.setBlurMipmapDirect(this.blurLast, this.mipmapLast);
    }

    public int getGlTextureId() {
        if (this.glTextureId == -1) {
            this.glTextureId = TextureUtil.glGenTextures();
        }

        return this.glTextureId;
    }

    @Getter
    private FilterState filterState = FilterState.NEAREST;

    @Override
    public void linearFilter() {

        if (this.filterState != FilterState.LINEAR) {
            this.filterState = FilterState.LINEAR;
            RenderSystem.linearFilter();
        }

    }

    @Override
    public void nearestFilter() {

        if (this.filterState != FilterState.NEAREST) {
            this.filterState = FilterState.NEAREST;
            RenderSystem.nearestFilter();
        }

    }

    public void deleteGlTexture() {
        if (this.glTextureId != -1) {
            TextureUtil.deleteTexture(this.glTextureId);
            this.glTextureId = -1;
        }
    }

    public void bindTexture() {

        if (this.getGlTextureId() == -1) {
            return;
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.getGlTextureId());

    }

    public void deleteTexture() {

        if (this.getGlTextureId() == -1) {
            return;
        }

        if (TritiumMusicExtension.isCallingFromMainThread()) {
            api.getGLStateManager().deleteTexture(this.getGlTextureId());
        } else {
            MultiThreadingUtil.runOnMainThreadBlocking(() -> {
                GL11.glDeleteTextures(this.getGlTextureId());
                return null;
            });
        }

    }
}
