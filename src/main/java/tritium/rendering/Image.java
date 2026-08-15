package tritium.rendering;

import org.lwjgl.opengl.GL11;
import tritium.interfaces.SharedConstants;
import tritium.management.FontManager;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.texture.ITextureObject;
import tritium.settings.ClientSettings;
import tritium.utils.Location;

public class Image implements SharedConstants {

    private static void renderDbgInfo(String str, double x, double y, double width, double height) {

        // show layout
        RenderSystem.drawOutLine(x, y, width, height, 0.5, 0x40FF0000);

        double lineLength = Math.min(8, Math.min(width * .25, height * .25));
        double lineSize = 1;
        int lineColor = 0x400090FF;
        // left top
        Rect.draw(x, y, lineLength, lineSize, lineColor);
        Rect.draw(x, y, lineSize, lineLength, lineColor);

        // right top
        Rect.draw(x + width - lineLength, y, lineLength, lineSize, lineColor);
        Rect.draw(x + width - lineSize, y, lineSize, lineLength, lineColor);

        // left bottom
        Rect.draw(x, y + height - lineLength, lineSize, lineLength, lineColor);
        Rect.draw(x, y + height - lineSize, lineLength, lineSize, lineColor);

        // right bottom
        Rect.draw(x + width - lineLength, y + height - lineSize, lineLength, lineSize, lineColor);
        Rect.draw(x + width - lineSize, y + height - lineLength, lineSize, lineLength, lineColor);
        
        CFontRenderer fr = FontManager.pf18;

        double sw = fr.getStringWidthD(str);
        double dbgX = Math.max(0, Math.min(RenderSystem.getWidth() - sw, x));
        double dbgY = Math.max(0, Math.min(RenderSystem.getHeight() - fr.getFontHeight(), y));
        fr.drawStringWithShadow(str, dbgX, dbgY, -1);
    }

    public static void draw(Location img, double x, double y, double width, double height) {
        draw(img, x, y, width, height, Type.Normal);
    }

    public static void draw(Location img, double x, double y, double width, double height, Type type) {
        draw(img, x, y, width, height, width, height, type);
    }

    public static void draw(ITextureObject img, double x, double y, double width, double height) {
        draw(img, x, y, width, height, Type.Normal);
    }

    public static void draw(ITextureObject img, double x, double y, double width, double height, Type type) {
        draw(img, x, y, width, height, width, height, type);
    }

    public static void drawLinear(Location img, double x, double y, double width, double height, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }
        
        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().enableTexture2D();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);

        if (textureObj != null) {
            TextureUtil.bindTexture(textureObj.getGlTextureId());
            RenderSystem.linearFilter();
            drawModalRectWithCustomSizedTexture(x, y, 0, 0, width, height, width, height);
        } else {
            textureObj = new DynamicTexture(img);
            TextureManager.getInstance().loadTexture(img, textureObj);
        }

        if (ClientSettings.SHOW_WIDGET_BOUNDARY) {
            int beginIndex = img.getResourcePath().indexOf("/");
            String str = img.getResourcePath().substring(beginIndex == -1 ? 0 : beginIndex + 1);
            renderDbgInfo(str, x, y, width, height);
        }
        api.getGLStateManager().enableAlpha();
    }

    public static void drawNearest(Location img, double x, double y, double width, double height, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().enableTexture2D();
//        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);
        if (textureObj != null) {
            TextureUtil.bindTexture(textureObj.getGlTextureId());
            RenderSystem.nearestFilter();
            drawModalRectWithCustomSizedTexture(x, y, 0, 0, width, height, width, height);
        } else {
            textureObj = new DynamicTexture(img);
            TextureManager.getInstance().loadTexture(img, textureObj);
        }

        if (ClientSettings.SHOW_WIDGET_BOUNDARY) {
            int beginIndex = img.getResourcePath().indexOf("/");
            String str = img.getResourcePath().substring(beginIndex == -1 ? 0 : beginIndex + 1);
            renderDbgInfo(str, x, y, width, height);
        }

        api.getGLStateManager().enableAlpha();
    }
    public static void drawLinearFlippedX(Location img, double x, double y, double width, double height, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().enableTexture2D();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);
        if (textureObj != null) {
            TextureUtil.bindTexture(textureObj.getGlTextureId());
            RenderSystem.linearFilter();
            drawModalRectWithCustomSizedTextureFlippedX(x, y, 0, 0, width, height, width, height);
        } else {
            textureObj = new DynamicTexture(img);
            TextureManager.getInstance().loadTexture(img, textureObj);
        }

        if (ClientSettings.SHOW_WIDGET_BOUNDARY) {
            int beginIndex = img.getResourcePath().indexOf("/");
            String str = img.getResourcePath().substring(beginIndex == -1 ? 0 : beginIndex + 1);
            renderDbgInfo(str, x, y, width, height);
        }

        api.getGLStateManager().enableAlpha();
    }

    public static void draw(ITextureObject img, double x, double y, double width, double height, double tWidth, double tHeight, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        api.getGLStateManager().enableTexture2D();
        TextureUtil.bindTexture(img.getGlTextureId());
        drawModalRectWithCustomSizedTexture(x, y, 0, 0, tWidth, tHeight, width, height);

        if (ClientSettings.SHOW_WIDGET_BOUNDARY) {
            renderDbgInfo("TexID: " + img.getGlTextureId(), x, y, width, height);
        }

//        api.getGLStateManager().enableAlpha();

    }

    public static void draw(Location img, double x, double y, double width, double height, double tWidth, double tHeight, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        api.getGLStateManager().enableTexture2D();
        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);
        if (textureObj != null) {
            TextureUtil.bindTexture(textureObj.getGlTextureId());
            drawModalRectWithCustomSizedTexture(x, y, 0, 0, tWidth, tHeight, width, height);
        } else {
            textureObj = new DynamicTexture(img);
            TextureManager.getInstance().loadTexture(img, textureObj);
        }

        if (ClientSettings.SHOW_WIDGET_BOUNDARY) {
            int beginIndex = img.getResourcePath().indexOf("/");
            String str = img.getResourcePath().substring(beginIndex == -1 ? 0 : beginIndex + 1);
            renderDbgInfo(str, x, y, width, height);
        }

        api.getGLStateManager().enableAlpha();
        
    }

    public static void draw(int textureId, double x, double y, double width, double height, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().enableTexture2D();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        api.getGLStateManager().bindTexture(textureId);

        drawModalRectWithCustomSizedTexture(x, y, 0, 0, width, height, width, height);

        if (ClientSettings.SHOW_WIDGET_BOUNDARY)
            renderDbgInfo("TexID: " + textureId, x, y, width, height);

        api.getGLStateManager().enableAlpha();
        
    }

    public static void drawModalRectWithCustomSizedTexture(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        double f = 1.0F / textureWidth;
        double f1 = 1.0F / textureHeight;

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d(u * f, (v + height) * f1);
        GL11.glVertex3d(x, y + height, 0.0D);
        GL11.glTexCoord2d((u + width) * f, (v + height) * f1);
        GL11.glVertex3d(x + width, y + height, 0.0D);
        GL11.glTexCoord2d((u + width) * f, v * f1);
        GL11.glVertex3d(x + width, y, 0.0D);
        GL11.glTexCoord2d(u * f, v * f1);
        GL11.glVertex3d(x, y, 0.0D);
        GL11.glEnd();

    }

    public static void drawLinearFlippedY(Location img, double x, double y, double width, double height, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);
        if (textureObj != null) {
            api.getGLStateManager().bindTexture(textureObj.getGlTextureId());
            textureObj.linearFilter();
            drawModalRectWithCustomSizedTextureFlippedY(x, y, 0, 0, width, height, width, height);
        }

        api.getGLStateManager().enableAlpha();
    }

    public static void drawLinearFlippedXAndY(Location img, double x, double y, double width, double height, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);
        if (textureObj != null) {
            api.getGLStateManager().bindTexture(textureObj.getGlTextureId());
            textureObj.linearFilter();
            drawModalRectWithCustomSizedTextureFlippedXAndY(x, y, 0, 0, width, height, width, height);
        }

        api.getGLStateManager().enableAlpha();
    }

    public static void drawModalRectWithCustomSizedTextureFlippedX(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        double f = 1.0F / textureWidth;
        double f1 = 1.0F / textureHeight;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d((u + width) * f, (v + height) * f1);
        GL11.glVertex3d(x, y + height, 0.0D);
        GL11.glTexCoord2d((u) * f, (v + height) * f1);
        GL11.glVertex3d(x + width, y + height, 0.0D);
        GL11.glTexCoord2d((u) * f, (v) * f1);
        GL11.glVertex3d(x + width, y, 0.0D);
        GL11.glTexCoord2d((u + width) * f, (v) * f1);
        GL11.glVertex3d(x, y, 0.0D);
        GL11.glEnd();
    }

    public static void drawModalRectWithCustomSizedTextureFlippedY(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        double f = 1.0F / textureWidth;
        double f1 = 1.0F / textureHeight;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d(u * f, (v) * f1);
        GL11.glVertex3d(x, y + height, 0.0D);
        GL11.glTexCoord2d((u + width) * f, (v) * f1);
        GL11.glVertex3d(x + width, y + height, 0.0D);
        GL11.glTexCoord2d((u + width) * f, (v + height) * f1);
        GL11.glVertex3d(x + width, y, 0.0D);
        GL11.glTexCoord2d(u * f, (v + height) * f1);
        GL11.glVertex3d(x, y, 0.0D);
        GL11.glEnd();
    }

    public static void drawModalRectWithCustomSizedTextureFlippedY(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight, float topAlpha, float bottomAlpha) {

        double f = 1.0F / textureWidth;
        double f1 = 1.0F / textureHeight;
        GL11.glBegin(GL11.GL_QUADS);

        // 顶部颜色
        GL11.glColor4f(1, 1, 1, topAlpha);

        GL11.glTexCoord2d(u * f, (v) * f1);
        GL11.glVertex3d(x, y + height, 0.0D);
        GL11.glTexCoord2d((u + width) * f, (v) * f1);
        GL11.glVertex3d(x + width, y + height, 0.0D);

        // 底部颜色
        GL11.glColor4f(1, 1, 1, bottomAlpha);

        GL11.glTexCoord2d((u + width) * f, (v + height) * f1);
        GL11.glVertex3d(x + width, y, 0.0D);
        GL11.glTexCoord2d(u * f, (v + height) * f1);
        GL11.glVertex3d(x, y, 0.0D);

        GL11.glEnd();

        GL11.glColor4f(1, 1, 1, 1);
    }

    public static void drawModalRectWithCustomSizedTextureFlippedXAndY(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        double f = 1.0F / textureWidth;
        double f1 = 1.0F / textureHeight;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d((u + width) * f, (v) * f1);
        GL11.glVertex3d(x, y + height, 0.0D);
        GL11.glTexCoord2d((u) * f, (v) * f1);
        GL11.glVertex3d(x + width, y + height, 0.0D);
        GL11.glTexCoord2d((u) * f, (v + height) * f1);
        GL11.glVertex3d(x + width, y, 0.0D);
        GL11.glTexCoord2d((u + width) * f, (v + height) * f1);
        GL11.glVertex3d(x, y, 0.0D);
        GL11.glEnd();
    }

    public static void drawModalRectWithCustomSizedTextureRotate90R(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        double f = 1.0F / textureWidth;
        double f1 = 1.0F / textureHeight;

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d((u + width) * f, (v + height) * f1);
        GL11.glVertex3d(x, y + height, 0.0D);
        GL11.glTexCoord2d((u + width) * f, (v) * f1);
        GL11.glVertex3d(x + width, y + height, 0.0D);
        GL11.glTexCoord2d((u) * f, (v) * f1);
        GL11.glVertex3d(x + width, y, 0.0D);
        GL11.glTexCoord2d((u) * f, (v + height) * f1);
        GL11.glVertex3d(x, y, 0.0D);
        GL11.glEnd();
    }

    public static void drawModalRectWithCustomSizedTextureRotate90L(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        double f = 1.0F / textureWidth;
        double f1 = 1.0F / textureHeight;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d((u + width) * f, (v) * f1);
        GL11.glVertex3d(x, y + height, 0.0D);
        GL11.glTexCoord2d((u + width) * f, (v + height) * f1);
        GL11.glVertex3d(x + width, y + height, 0.0D);
        GL11.glTexCoord2d((u) * f, (v + height) * f1);
        GL11.glVertex3d(x + width, y, 0.0D);
        GL11.glTexCoord2d((u) * f, (v) * f1);
        GL11.glVertex3d(x, y, 0.0D);
        GL11.glEnd();
    }

    public static void draw(double x, double y, double width, double height, Type type) {

        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        api.getGLStateManager().enableTexture2D();
//        api.getGLStateManager().bindTexture(textureId);

        drawModalRectWithCustomSizedTexture(x, y, 0, 0, width, height, width, height);

        api.getGLStateManager().enableAlpha();
        
    }


    public enum Type {
        NoColor, Normal
    }
}
