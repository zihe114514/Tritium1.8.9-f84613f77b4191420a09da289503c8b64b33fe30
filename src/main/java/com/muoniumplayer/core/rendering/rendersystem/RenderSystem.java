package com.muoniumplayer.core.rendering.rendersystem;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import today.opai.api.OpenAPI;
import today.opai.api.events.EventRender2D;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.render.GLStateManager;
import today.opai.api.interfaces.render.WindowResolution;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.Framebuffer;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;

import java.awt.*;
import java.util.UUID;

import static org.lwjgl.opengl.GL11.*;

/**
 * @author IzumiiKonata
 * @since 4/15/2023 8:47 PM
 */
public class RenderSystem implements SharedConstants {
    public static final Object ASYNC_LOCK = new Object();
    public static final float DIVIDE_BY_255 = 0.003921568627451F;
    @Getter
    @Setter
    private static double frameDeltaTime = 0;


    public static void setBlurMipmapDirect(boolean blur, boolean mipmap) {
        int minFilter = -1;
        int magFilter = -1;
        if (blur) {
            minFilter = mipmap ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR;
            magFilter = GL11.GL_LINEAR;
        } else {
            minFilter = mipmap ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST;
            magFilter = GL11.GL_NEAREST;
        }

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter);
    }

    public static void linearFilter() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

    public static void nearestFilter() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }

    private static int scaleFactor, width, height;

    public static double getScaleMultiplier() {
        return getScaleFactor() * 0.5;
    }

    @SneakyThrows
    public static int getScaleFactor() {
        return scaleFactor;
    }

    static {
        api.registerEvent(new EventHandler() {
            @Override
            public void onRender2D(EventRender2D event) {
                WindowResolution wr = event.getWindowResolution();

                scaleFactor = wr.getScaleFactor();
                width = wr.getWidth();
                height = wr.getHeight();
            }
        });
    }
    
    public static boolean FIXED_SCALE = false;

    /**
     * 重新读取当前 ScaledResolution（响应窗口尺寸 / GUI Scale 变化）。
     * width/height/scaleFactor 仅在 RenderGameOverlayEvent（游戏内 HUD 渲染）里刷新，
     * 而 GuiScreen（NCMScreen/CoverflowOverlay）打开期间该事件不触发，导致字段停留在上一次
     * 游戏帧的值。窗口 resize / 改 GUI Scale 后若不在此处刷新，屏幕坐标映射与布局会错位。
     * 各 GuiScreen 应在 drawScreen 开头调用一次。
     */
    public static void refreshResolution() {
        net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(
                net.minecraft.client.Minecraft.getMinecraft());
        scaleFactor = sr.getScaleFactor();
        width = sr.getScaledWidth();
        height = sr.getScaledHeight();
    }

    public static double getWidthNotScaled() {
        return width;
    }

    public static double getHeightNotScaled() {
        return height;
    }

    public static double getWidth() {
        if (!FIXED_SCALE) {
            return width;
        }

        return getFixedWidth() * .5;
    }

    public static double getHeight() {
        if (!FIXED_SCALE) {
            return height;
        }

        return getFixedHeight() * .5;
    }

    public static double getFixedWidth() {
        return Math.min(Display.getWidth(), 1920);
    }

    public static double getFixedHeight() {
        double scaleFactor = Display.getWidth() / getFixedWidth();
        return Display.getHeight() / scaleFactor;
    }

    public static void color(int color) {
        float f = (color >> 24 & 255) * DIVIDE_BY_255;
        float f1 = (color >> 16 & 255) * DIVIDE_BY_255;
        float f2 = (color >> 8 & 255) * DIVIDE_BY_255;
        float f3 = (color & 255) * DIVIDE_BY_255;
        api.getGLStateManager().color(f1, f2, f3, f);
    }

    public static void drawRect(double left, double top, double right, double bottom, int color) {

        if (left > right) {
            double i = left;
            left = right;
            right = i;
        }

        if (top > bottom) {
            double j = top;
            top = bottom;
            bottom = j;
        }


//        Tessellator tessellator = Tessellator.getInstance();
//        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
//        boolean texture2DEnabled = api.getGLStateManager().isTexture2DEnabled();
//        if (texture2DEnabled)
        api.getGLStateManager().disableTexture2D();

        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        RenderSystem.color(color);

        GL11.glBegin(GL_TRIANGLE_STRIP);

        GL11.glVertex2d(left, bottom);
        GL11.glVertex2d(right, bottom);
        GL11.glVertex2d(left, top);
        GL11.glVertex2d(right, top);

        GL11.glEnd();

//        worldrenderer.begin(7, DefaultVertexFormats.POSITION);
//        worldrenderer.pos(left, bottom, 0.0D).endVertex();
//        worldrenderer.pos(right, bottom, 0.0D).endVertex();
//        worldrenderer.pos(right, top, 0.0D).endVertex();
//        worldrenderer.pos(left, top, 0.0D).endVertex();
//
//        tessellator.draw();
//        if (texture2DEnabled)
//            api.getGLStateManager().enableTexture2D();
//        api.getGLStateManager().enableTexture2D();
//        api.getGLStateManager().disableBlend();

//        RenderSystem.resetColor();
    }

    public static void resetColor() {
        RenderSystem.color(-1);
    }

    public static void drawGradientRectLeftToRight(final double left, final double top, final double right, final double bottom, final int startColor, final int endColor) {
        final float sa = (startColor >> 24 & 0xFF) * 0.003921568627451F;
        final float sr = (startColor >> 16 & 0xFF) * 0.003921568627451F;
        final float sg = (startColor >> 8 & 0xFF) * 0.003921568627451F;
        final float sb = (startColor & 0xFF) * 0.003921568627451F;
        final float ea = (endColor >> 24 & 0xFF) * 0.003921568627451F;
        final float er = (endColor >> 16 & 0xFF) * 0.003921568627451F;
        final float eg = (endColor >> 8 & 0xFF) * 0.003921568627451F;
        final float eb = (endColor & 0xFF) * 0.003921568627451F;
        api.getGLStateManager().disableTexture2D();
        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        api.getGLStateManager().shadeModel(GL11.GL_SMOOTH);

        GL11.glBegin(GL_QUADS);

        api.getGLStateManager().color(sr, sg, sb, sa);
        GL11.glVertex2d(left, bottom);
        api.getGLStateManager().color(er, eg, eb, ea);
        GL11.glVertex2d(right, bottom);
        api.getGLStateManager().color(er, eg, eb, ea);
        GL11.glVertex2d(right, top);
        api.getGLStateManager().color(sr, sg, sb, sa);
        GL11.glVertex2d(left, top);

        GL11.glEnd();

//        final Tessellator tessellator = Tessellator.getInstance();
//        final WorldRenderer worldrenderer = tessellator.getWorldRenderer();
//        worldrenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
//        worldrenderer.pos(left, bottom, 0.0).color(sr, sg, sb, sa).endVertex();
//        worldrenderer.pos(right, bottom, 0.0).color(er, eg, eb, ea).endVertex();
//        worldrenderer.pos(right, top, 0.0).color(er, eg, eb, ea).endVertex();
//        worldrenderer.pos(left, top, 0.0).color(sr, sg, sb, sa).endVertex();
//        tessellator.draw();
        api.getGLStateManager().shadeModel(GL11.GL_FLAT);
//        api.getGLStateManager().disableBlend();
//        api.getGLStateManager().enableAlpha();
//        api.getGLStateManager().enableTexture2D();
    }
//
//    public static void drawGradientRectBottomToTop(final double left, final double top, final double right, final double bottom, final int startColor, final int endColor) {
//        final float sa = (startColor >> 24 & 0xFF) * 0.003921568627451F;
//        final float sr = (startColor >> 16 & 0xFF) * 0.003921568627451F;
//        final float sg = (startColor >> 8 & 0xFF) * 0.003921568627451F;
//        final float sb = (startColor & 0xFF) * 0.003921568627451F;
//        final float ea = (endColor >> 24 & 0xFF) * 0.003921568627451F;
//        final float er = (endColor >> 16 & 0xFF) * 0.003921568627451F;
//        final float eg = (endColor >> 8 & 0xFF) * 0.003921568627451F;
//        final float eb = (endColor & 0xFF) * 0.003921568627451F;
//        api.getGLStateManager().disableTexture2D();
//        api.getGLStateManager().enableBlend();
//        api.getGLStateManager().disableAlpha();
//        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
//        api.getGLStateManager().shadeModel(GL11.GL_SMOOTH);
//        final Tessellator tessellator = Tessellator.getInstance();
//        final WorldRenderer worldrenderer = tessellator.getWorldRenderer();
//        worldrenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
//        worldrenderer.pos(left, bottom, 0.0).color(sr, sg, sb, sa).endVertex();
//        worldrenderer.pos(right, bottom, 0.0).color(sr, sg, sb, sa).endVertex();
//        worldrenderer.pos(right, top, 0.0).color(er, eg, eb, ea).endVertex();
//        worldrenderer.pos(left, top, 0.0).color(er, eg, eb, ea).endVertex();
//        tessellator.draw();
//        api.getGLStateManager().shadeModel(GL11.GL_FLAT);
//        api.getGLStateManager().disableBlend();
//        api.getGLStateManager().enableAlpha();
//        api.getGLStateManager().enableTexture2D();
//    }
//
//    public static void drawGradientRectTopToBottom(final double left, final double top, final double right, final double bottom, final int startColor, final int endColor) {
//        final float sa = (startColor >> 24 & 0xFF) * 0.003921568627451F;
//        final float sr = (startColor >> 16 & 0xFF) * 0.003921568627451F;
//        final float sg = (startColor >> 8 & 0xFF) * 0.003921568627451F;
//        final float sb = (startColor & 0xFF) * 0.003921568627451F;
//        final float ea = (endColor >> 24 & 0xFF) * 0.003921568627451F;
//        final float er = (endColor >> 16 & 0xFF) * 0.003921568627451F;
//        final float eg = (endColor >> 8 & 0xFF) * 0.003921568627451F;
//        final float eb = (endColor & 0xFF) * 0.003921568627451F;
//        api.getGLStateManager().disableTexture2D();
//        api.getGLStateManager().enableBlend();
//        api.getGLStateManager().disableAlpha();
//        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
//        api.getGLStateManager().shadeModel(GL11.GL_SMOOTH);
//        final Tessellator tessellator = Tessellator.getInstance();
//        final WorldRenderer worldrenderer = tessellator.getWorldRenderer();
//        worldrenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
//        worldrenderer.pos(left, bottom, 0.0).color(er, eg, eb, ea).endVertex();
//        worldrenderer.pos(right, bottom, 0.0).color(er, eg, eb, ea).endVertex();
//        worldrenderer.pos(right, top, 0.0).color(sr, sg, sb, sa).endVertex();
//        worldrenderer.pos(left, top, 0.0).color(sr, sg, sb, sa).endVertex();
//        tessellator.draw();
//        api.getGLStateManager().shadeModel(GL11.GL_FLAT);
//        api.getGLStateManager().disableBlend();
//        api.getGLStateManager().enableAlpha();
//        api.getGLStateManager().enableTexture2D();
//    }

    public static boolean isHovered(double mouseX, double mouseY, double startX, double startY, double width, double height) {

        if (width < 0) {
            width = -width;
            startX -= width;
        }

        if (height < 0) {
            height = -height;
            startY -= height;
        }

        return mouseX >= startX && mouseY >= startY && mouseX <= startX + width && mouseY <= startY + height;
    }

    public static boolean isHovered(double mouseX, double mouseY, double startX, double startY, double width, double height, double shrink) {
        return RenderSystem.isHovered(mouseX, mouseY, startX + shrink, startY + shrink, width - shrink * 2, height - shrink * 2);
    }

    public static Framebuffer createFrameBuffer(Framebuffer framebuffer) {
        return createFrameBuffer(framebuffer, Display.getWidth(), Display.getHeight());
    }

    public static Framebuffer createFrameBuffer(Framebuffer framebuffer, int width, int height) {
        if (framebuffer == null) {
            return new Framebuffer(width, height, true);
        } else if (framebuffer.framebufferWidth != width || framebuffer.framebufferHeight != height) {
            framebuffer.createBindFramebuffer(width, height);
        }
        return framebuffer;
    }

    public static Framebuffer createFrameBufferNoDepth(Framebuffer framebuffer) {
        if (framebuffer == null || framebuffer.framebufferWidth != width || framebuffer.framebufferHeight != height) {
            if (framebuffer != null) {
                framebuffer.deleteFramebuffer();
            }
            return new Framebuffer(width, height, false);
        }
        return framebuffer;
    }

    public static Framebuffer createDownScaledFrameBuffer(Framebuffer framebuffer, double factor) {
        if (framebuffer == null || framebuffer.framebufferWidth != (int) (width * factor) || framebuffer.framebufferHeight != (int) (height * factor)) {
            if (framebuffer != null) {
                framebuffer.deleteFramebuffer();
            }
            return new Framebuffer((int) (width * factor), (int) (height * factor), false);
        }
        return framebuffer;
    }

    public static void setAlphaLimit(float limit) {
        api.getGLStateManager().enableAlpha();
        api.getGLStateManager().alphaFunc(GL_GREATER, (float) (limit * .01));
    }

    public static void bindTexture(int textureId) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }

    public static double getMouseX() {
        return Mouse.getX() * RenderSystem.getScaleFactor();
    }

    public static double getMouseY() {
        return Mouse.getY() * RenderSystem.getScaleFactor();
    }

    public static void translateAndScale(double posX, double posY, double scale) {

        api.getGLStateManager().translate(posX, posY, 0);
        api.getGLStateManager().scale(scale, scale, 2);
        api.getGLStateManager().translate(-posX, -posY, 0);

    }

    public static void drawOutLine(double x, double y, double width, double height, double thickness, int color) {
//        RenderSystem.color(color);

        Rect.draw(x - thickness, y - thickness, width + thickness * 2, thickness, color, Rect.RectType.EXPAND);
        Rect.draw(x - thickness, y - thickness, thickness, height + thickness, color, Rect.RectType.EXPAND);
        Rect.draw(x + width, y - thickness, thickness, height + thickness, color, Rect.RectType.EXPAND);
        Rect.draw(x - thickness, y + height, width + thickness * 2, thickness, color, Rect.RectType.EXPAND);
    }

    public static Color getOppositeColor(Color colorIn) {
        return new Color(255 - colorIn.getRed(), 255 - colorIn.getGreen(), 255 - colorIn.getBlue(), colorIn.getAlpha());
    }

    public static int getOppositeColorHex(int colorHex) {
        return getOppositeColor(new Color(colorHex, true)).getRGB();
    }

    public static int cRange(int c) {
        if (c < 0) {
            c = 0;
        }

        if (c > 255) {
            c = 255;
        }

        return c;
    }

    public static int reAlpha(int color, float alpha) {
        if (alpha > 1) {
            alpha = 1;
        }

        if (alpha < 0) {
            alpha = 0;
        }
        return RGBA.color((color >> 16) & 0xFF, (color >> 8) & 0xFF, (color) & 0xFF, (int) (alpha * 255));
    }

}
