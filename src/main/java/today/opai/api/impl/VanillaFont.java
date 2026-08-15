package today.opai.api.impl;

import net.minecraft.client.gui.FontRenderer;
import today.opai.api.interfaces.render.Font;

public class VanillaFont implements Font {

    private final FontRenderer renderer;

    public VanillaFont(FontRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public int getWidth(String text) {
        return renderer.getStringWidth(text);
    }

    @Override
    public int getHeight() {
        return renderer.FONT_HEIGHT;
    }

    @Override
    public void drawString(String text, double x, double y, int color) {
        renderer.drawString(text, (int) x, (int) y, color);
    }
}
