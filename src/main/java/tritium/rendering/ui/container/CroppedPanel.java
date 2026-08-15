package tritium.rendering.ui.container;


import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;

/**
 * 裁剪。
 * @author IzumiiKonata
 * Date: 2025/7/8 22:04
 */
public class CroppedPanel extends Panel {

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        StencilClipManager.beginClip(() -> Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), -1));

        super.renderWidget(mouseX, mouseY, dWheel);

        StencilClipManager.endClip();
    }

}
