package today.opai.api.interfaces.render;

/**
 * 对应原项目 today.opai.api.interfaces.render.GLStateManager。
 * 方法签名与实际调用点一一对应（1.8.9 下由 LWJGL 2 / GL11 实现）。
 */
public interface GLStateManager {

    void pushMatrix();

    void popMatrix();

    void pushAttrib();

    void popAttrib();

    void resetColor();

    void translate(double x, double y, double z);

    void scale(double x, double y, double z);

    void rotate(float angle, float x, float y, float z);

    void color(float r, float g, float b, float a);

    void alphaFunc(int func, float ref);

    void blendFunc(int srcFactor, int dstFactor);

    void tryBlendFuncSeparate(int srcFactor, int dstFactor, int srcFactorAlpha, int dstFactorAlpha);

    void shadeModel(int mode);

    void matrixMode(int mode);

    void loadIdentity();

    void ortho(double left, double right, double bottom, double top, double zNear, double zFar);

    void clear(int mask);

    void clearColor(float r, float g, float b, float a);

    void clearDepth(double depth);

    void viewport(int x, int y, int width, int height);

    void colorMask(boolean r, boolean g, boolean b, boolean a);

    void depthMask(boolean flag);

    void bindTexture(int texture);

    void deleteTexture(int texture);

    int generateTexture();

    void setActiveTexture(int textureUnit);

    void callList(int list);

    void enableBlend();

    void disableBlend();

    void enableAlpha();

    void disableAlpha();

    void enableTexture2D();

    void disableTexture2D();

    void enableDepth();

    void disableDepth();

    void enableCull();

    void disableCull();

    void enableLighting();

    void disableLighting();

    void enableColorMaterial();
}
