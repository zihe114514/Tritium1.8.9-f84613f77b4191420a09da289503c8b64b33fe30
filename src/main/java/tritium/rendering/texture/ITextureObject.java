package tritium.rendering.texture;

public interface ITextureObject {
    void setBlurMipmap(boolean p_174936_1_, boolean p_174936_2_);

    void restoreLastBlurMipmap();

    int getGlTextureId();

    void linearFilter();
    void nearestFilter();
}
