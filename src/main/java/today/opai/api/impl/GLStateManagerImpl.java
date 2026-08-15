package today.opai.api.impl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import today.opai.api.interfaces.render.GLStateManager;

/**
 * 基于 LWJGL 2 的 GLStateManager 实现。
 * 原项目即运行在裸 LWJGL 2 上下文（Opai 无 Minecraft GlStateManager），
 * 此处直接透传 GL11/GL13/GL14，保持与原实现一致。
 */
public class GLStateManagerImpl implements GLStateManager {

    @Override
    public void pushMatrix() {
        GL11.glPushMatrix();
    }

    @Override
    public void popMatrix() {
        GL11.glPopMatrix();
    }

    @Override
    public void pushAttrib() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
    }

    @Override
    public void popAttrib() {
        GL11.glPopAttrib();
    }

    @Override
    public void resetColor() {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void translate(double x, double y, double z) {
        GL11.glTranslated(x, y, z);
    }

    @Override
    public void scale(double x, double y, double z) {
        GL11.glScaled(x, y, z);
    }

    @Override
    public void rotate(float angle, float x, float y, float z) {
        GL11.glRotatef(angle, x, y, z);
    }

    @Override
    public void color(float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
    }

    @Override
    public void alphaFunc(int func, float ref) {
        GL11.glAlphaFunc(func, ref);
    }

    @Override
    public void blendFunc(int srcFactor, int dstFactor) {
        GL11.glBlendFunc(srcFactor, dstFactor);
    }

    @Override
    public void tryBlendFuncSeparate(int srcFactor, int dstFactor, int srcFactorAlpha, int dstFactorAlpha) {
        GL14.glBlendFuncSeparate(srcFactor, dstFactor, srcFactorAlpha, dstFactorAlpha);
    }

    @Override
    public void shadeModel(int mode) {
        GL11.glShadeModel(mode);
    }

    @Override
    public void matrixMode(int mode) {
        GL11.glMatrixMode(mode);
    }

    @Override
    public void loadIdentity() {
        GL11.glLoadIdentity();
    }

    @Override
    public void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        GL11.glOrtho(left, right, bottom, top, zNear, zFar);
    }

    @Override
    public void clear(int mask) {
        GL11.glClear(mask);
    }

    @Override
    public void clearColor(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
    }

    @Override
    public void clearDepth(double depth) {
        GL11.glClearDepth(depth);
    }

    @Override
    public void viewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
    }

    @Override
    public void colorMask(boolean r, boolean g, boolean b, boolean a) {
        GL11.glColorMask(r, g, b, a);
    }

    @Override
    public void depthMask(boolean flag) {
        GL11.glDepthMask(flag);
    }

    @Override
    public void bindTexture(int texture) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    @Override
    public void deleteTexture(int texture) {
        GL11.glDeleteTextures(texture);
    }

    @Override
    public int generateTexture() {
        return GL11.glGenTextures();
    }

    @Override
    public void setActiveTexture(int textureUnit) {
        GL13.glActiveTexture(textureUnit);
    }

    @Override
    public void callList(int list) {
        GL11.glCallList(list);
    }

    @Override
    public void enableBlend() {
        GL11.glEnable(GL11.GL_BLEND);
    }

    @Override
    public void disableBlend() {
        GL11.glDisable(GL11.GL_BLEND);
    }

    @Override
    public void enableAlpha() {
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }

    @Override
    public void disableAlpha() {
        GL11.glDisable(GL11.GL_ALPHA_TEST);
    }

    @Override
    public void enableTexture2D() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    @Override
    public void disableTexture2D() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    @Override
    public void enableDepth() {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    @Override
    public void disableDepth() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    @Override
    public void enableCull() {
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    @Override
    public void disableCull() {
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    @Override
    public void enableLighting() {
        GL11.glEnable(GL11.GL_LIGHTING);
    }

    @Override
    public void disableLighting() {
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    @Override
    public void enableColorMaterial() {
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
    }
}
