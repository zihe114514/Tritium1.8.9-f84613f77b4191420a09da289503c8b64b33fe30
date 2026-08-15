package tritium.rendering.ui.widgets;

import lombok.Getter;

import tritium.rendering.animation.Interpolations;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;

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

        int w = fr.getStringWidth(icon);
        double h = fr.getFontHeight();

        fr.drawString(icon, this.getX() + this.getWidth() * 0.5 - w * 0.5 + fontOffsetX, this.getY() + this.getHeight() * 0.5 - h * 0.5 + fontOffsetY, this.getHexColor());
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
