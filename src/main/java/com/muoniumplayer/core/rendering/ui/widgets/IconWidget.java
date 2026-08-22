package com.muoniumplayer.core.rendering.ui.widgets;

import lombok.Getter;

import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInk;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;

/**
 * @author IzumiiKonata
 * Date: 2025/10/8 19:13
 */
public class IconWidget extends AbstractWidget<IconWidget> {

    @Getter
    private String icon;

    public CFontRenderer fr;

    public IconWidget(String icon, CFontRenderer fr, double x, double y, double width, double height) {
        this.icon = icon;
        this.fr = fr;
        this.setBounds(x, y, width, height);
        this.setShouldOverrideMouseCursor(true);
    }

    float alphaAnim = 0f, alphaAnim2 = 0f;
    public double fontOffsetX = 0, fontOffsetY = 0;

    /**
     * 可选：按字形墨迹居中，而不是按字距框居中。{@code null} 时行为与以前完全一致。
     *
     * <p>默认那条路径用 {@code getStringWidth()} 与 {@code getFontHeight()} 居中，它们是排版度量：
     * 正文要靠它们对齐基线，但单个图标要的是墨迹落在按钮正中。两者相差可以到 2 像素，在 20 像素
     * 的按钮里肉眼就能看出偏移。设上这份度量之后落点只由墨迹决定，和行高经验值无关，因此也不受
     * "字形还没烘好时 {@code getFontHeight()} 尚未初始化"的影响。</p>
     *
     * <p>刻意做成可选而不是直接改默认行为：项目里其它图标字体的位置都是在字距框居中的前提下调好的。</p>
     */
    public GlyphInk inkMetrics;

    boolean run = false;

    @Override
    public void onRender(double mouseX, double mouseY) {
        api.getGLStateManager().disableAlpha();

        int alpha = (int) (this.getAlpha() * 255);

        double size = this.getWidth() * .5;

        if (alphaAnim != 0f) {
            float a = Math.min(alphaAnim, alpha);
            roundedRect(this.getX() + this.getWidth() * 0.5 - size, this.getY() + this.getHeight() * 0.5 - size, size * 2, size * 2, size - 0.5, reAlpha(this.getHexColor(), a));
        }

        if (alphaAnim2 != 0f) {
            float a = Math.min(alphaAnim2, alpha);
            roundedRect(this.getX() + this.getWidth() * 0.5 - size, this.getY() + this.getHeight() * 0.5 - size, size * 2, size * 2, size - 0.5, reAlpha(this.getHexColor(), a));
        }

        if (run) {
            alphaAnim2 = Interpolations.interpolate(alphaAnim2, 40 * RenderSystem.DIVIDE_BY_255, 0.2f);

            if (Math.abs(alphaAnim2 - 40 * RenderSystem.DIVIDE_BY_255) < 0.05f) {
                run = false;
            }
        } else {
            alphaAnim2 = Interpolations.interpolate(alphaAnim2, 0, 0.2f);
        }

        if (this.isHovering()) {
            alphaAnim = Interpolations.interpolate(alphaAnim, 40 * RenderSystem.DIVIDE_BY_255, 0.2f);
        } else {
            alphaAnim = Interpolations.interpolate(alphaAnim, 0, 0.2f);
        }

        double drawX, drawY;
        if (inkMetrics != null) {
            // drawString 把位图左上角画在 (x, y - 2)，并整体缩放 0.5，所以墨迹中心的屏幕位置是
            // (drawX + centerX * 0.5, drawY - 2 + centerY * 0.5)。反解成"墨迹中心 = 按钮中心"。
            drawX = this.getX() + this.getWidth() * 0.5 - inkMetrics.centerX() * 0.5 + fontOffsetX;
            drawY = this.getY() + this.getHeight() * 0.5 - inkMetrics.centerY() * 0.5 + 2.0 + fontOffsetY;
        } else {
            int w = fr.getStringWidth(icon);
            double h = fr.getFontHeight();
            drawX = this.getX() + this.getWidth() * 0.5 - w * 0.5 + fontOffsetX;
            drawY = this.getY() + this.getHeight() * 0.5 - h * 0.5 + fontOffsetY;
        }

        fr.drawString(icon, drawX, drawY, this.getHexColor());
    }

    @Override
    public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {

        this.run = true;

        return super.onMouseClicked(relativeX, relativeY, mouseButton);
    }

    public IconWidget setIcon(String icon) {
        this.icon = icon;
        return this;
    }
}
