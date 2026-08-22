package com.muoniumplayer.core.rendering.ui.widgets;

import com.muoniumplayer.core.rendering.Image;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.utils.Location;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * A clickable, theme-tintable texture icon with the same hover/click feedback
 * used by {@link IconWidget}.  The supplied textures are white glyphs on a
 * transparent background, so the widget can safely tint them for every player
 * theme without introducing a rectangular image background.
 */
public class ThemedTextureIconWidget extends AbstractWidget<ThemedTextureIconWidget> {

    private final Supplier<Location> iconLocationSupplier;
    private final Supplier<String> fallbackIconSupplier;
    private final CFontRenderer fallbackFont;

    private float hoverAlpha;
    private float clickAlpha;
    private boolean clicked;

    public ThemedTextureIconWidget(Supplier<Location> iconLocationSupplier,
                                    Supplier<String> fallbackIconSupplier,
                                    CFontRenderer fallbackFont,
                                    double x, double y, double width, double height) {
        this.iconLocationSupplier = iconLocationSupplier;
        this.fallbackIconSupplier = fallbackIconSupplier;
        this.fallbackFont = fallbackFont;
        this.setBounds(x, y, width, height);
        this.setShouldOverrideMouseCursor(true);
    }

    public ThemedTextureIconWidget(Location iconLocation, CFontRenderer fallbackFont,
                                    double x, double y, double width, double height) {
        this(() -> iconLocation, () -> null, fallbackFont, x, y, width, height);
    }

    public ThemedTextureIconWidget(Location iconLocation, String fallbackIcon, CFontRenderer fallbackFont,
                                    double x, double y, double width, double height) {
        this(() -> iconLocation, () -> fallbackIcon, fallbackFont, x, y, width, height);
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        api.getGLStateManager().disableAlpha();
        renderInteractionFeedback();

        Location iconLocation = iconLocationSupplier == null ? null : iconLocationSupplier.get();
        ITextureObject texture = null;
        if (iconLocation != null) {
            try {
                TextureManager.getInstance().bindTexture(iconLocation);
                // 这些图标是 96×96 的画布，要缩进 ~20 逻辑像素的按钮里（约 4.8 倍缩小）。
                // DynamicTexture 默认走 GL_NEAREST，那样 3 像素粗的描边会被整段抽掉，
                // 图标看上去缺边少角、也比相邻的图标字体小一圈。改成线性采样后描边还在。
                RenderSystem.linearFilter();
                texture = TextureManager.getInstance().getTexture(iconLocation);
            } catch (Throwable ignored) {
                // A resource pack or an outdated development jar can omit an
                // optional texture. Keep the established font-icon fallback
                // rather than crashing while rendering the player screen.
                texture = null;
            }
        }

        if (texture != null) {
            Color tint = this.getColor();
            api.getGLStateManager().color(
                    tint.getRed() / 255.0f,
                    tint.getGreen() / 255.0f,
                    tint.getBlue() / 255.0f,
                    tint.getAlpha() / 255.0f
            );
            Image.draw(texture, this.getX(), this.getY(), this.getWidth(), this.getHeight(), Image.Type.NoColor);
            api.getGLStateManager().color(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }

        drawFallbackIcon();
    }

    private void renderInteractionFeedback() {
        int alpha = (int) (this.getAlpha() * 255.0f);
        double radius = this.getWidth() * 0.5;
        double x = this.getX() + this.getWidth() * 0.5 - radius;
        double y = this.getY() + this.getHeight() * 0.5 - radius;

        if (this.hoverAlpha != 0.0f) {
            roundedRect(x, y, radius * 2.0, radius * 2.0, radius - 0.5,
                    reAlpha(this.getHexColor(), Math.min(this.hoverAlpha, alpha)));
        }
        if (this.clickAlpha != 0.0f) {
            roundedRect(x, y, radius * 2.0, radius * 2.0, radius - 0.5,
                    reAlpha(this.getHexColor(), Math.min(this.clickAlpha, alpha)));
        }

        if (this.clicked) {
            this.clickAlpha = Interpolations.interpolate(this.clickAlpha, 40.0f * RenderSystem.DIVIDE_BY_255, 0.2f);
            if (Math.abs(this.clickAlpha - 40.0f * RenderSystem.DIVIDE_BY_255) < 0.05f) {
                this.clicked = false;
            }
        } else {
            this.clickAlpha = Interpolations.interpolate(this.clickAlpha, 0.0f, 0.2f);
        }

        this.hoverAlpha = Interpolations.interpolate(this.hoverAlpha,
                this.isHovering() ? 40.0f * RenderSystem.DIVIDE_BY_255 : 0.0f, 0.2f);
    }

    private void drawFallbackIcon() {
        if (fallbackFont == null || fallbackIconSupplier == null) {
            return;
        }
        String fallbackIcon = fallbackIconSupplier.get();
        if (fallbackIcon == null || fallbackIcon.isEmpty()) {
            return;
        }

        int width = fallbackFont.getStringWidth(fallbackIcon);
        double height = fallbackFont.getFontHeight();
        fallbackFont.drawString(fallbackIcon,
                this.getX() + this.getWidth() * 0.5 - width * 0.5,
                this.getY() + this.getHeight() * 0.5 - height * 0.5,
                this.getHexColor());
    }

    @Override
    public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
        this.clicked = true;
        return super.onMouseClicked(relativeX, relativeY, mouseButton);
    }
}
