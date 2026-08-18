package com.muoniumplayer.core.screens.ncm;

import org.lwjgl.input.Mouse;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.utils.cursor.CursorUtils;

/**
 * Shared volume slider used by both player surfaces.
 *
 * <p>The control owns only interaction/animation state. The actual volume is
 * persisted and applied by {@link CloudMusic}, keeping the full-screen player,
 * compact controls and configurable hotkeys in sync.</p>
 */
public final class VolumeControl implements SharedRenderingConstants {

    private static final double HIT_HEIGHT = 18.0;
    private static final double MIN_BAR_HEIGHT = 4.5;
    private static final double MAX_BAR_HEIGHT = 7.5;
    private static final double ENDPOINT_GAP = 6.0;

    private double renderedBarHeight = MIN_BAR_HEIGHT;
    private boolean dragging;

    /**
     * Renders the common full-screen-player volume presentation: a low/high
     * volume glyph framing one smoothly animated slider.
     *
     * @param x complete control left edge (including the two glyphs)
     * @param width complete control width (including the two glyphs)
     */
    public boolean render(double mouseX, double mouseY, double x, double centerY,
                          double width, float alpha) {
        if (width <= 1.0 || alpha <= .01f) {
            dragging = false;
            return false;
        }

        double lowGlyphWidth = FontManager.music40.getStringWidthD("I");
        double highGlyphWidth = FontManager.music40.getStringWidthD("J");
        double sliderX = x + lowGlyphWidth + ENDPOINT_GAP;
        double sliderWidth = Math.max(1.0, width - lowGlyphWidth - highGlyphWidth - ENDPOINT_GAP * 2.0);

        boolean hovering = isHovered(mouseX, mouseY, sliderX, centerY - HIT_HEIGHT * .5,
                sliderWidth, HIT_HEIGHT);
        boolean leftDown = Mouse.isButtonDown(0);
        if (!leftDown) {
            dragging = false;
        } else if (hovering) {
            dragging = true;
        }

        if (dragging) {
            double rawPercent = (mouseX - sliderX) / sliderWidth;
            CloudMusic.setVolume((float) rawPercent, true);
        }

        renderedBarHeight = Interpolations.interpolate(renderedBarHeight,
                (hovering || dragging) ? MAX_BAR_HEIGHT : MIN_BAR_HEIGHT, .28f);
        double radius = Math.min(renderedBarHeight * .5, 2.8);
        int trackColor = RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.BORDER),
                alpha * ((hovering || dragging) ? .92f : .68f));
        int fillColor = RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), alpha);
        int iconColor = RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                alpha * ((hovering || dragging) ? .95f : .64f));

        roundedRect(sliderX, centerY - renderedBarHeight * .5, sliderWidth, renderedBarHeight,
                radius, trackColor);

        double filledWidth = sliderWidth * CloudMusic.getVolume();
        if (filledWidth > .05) {
            roundedRect(sliderX, centerY - renderedBarHeight * .5, filledWidth, renderedBarHeight,
                    Math.min(radius, filledWidth * .5), fillColor);
        }

        // A small thumb is only visible while interacting; the resting state stays as compact
        // as the original full-screen control while the current value remains easy to target.
        if (hovering || dragging) {
            double thumbX = sliderX + Math.max(0.0, Math.min(sliderWidth, filledWidth));
            roundedRect(thumbX - 1.7, centerY - 1.7, 3.4, 3.4, 1.7,
                    RenderSystem.reAlpha(0x00FFFFFF, alpha * .94f));
        }

        double iconY = centerY - FontManager.music40.getHeight() * .5 - .5;
        FontManager.music40.drawString("I", x, iconY, iconColor);
        FontManager.music40.drawString("J", x + width - highGlyphWidth, iconY, iconColor);

        if (hovering || dragging) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }
        return hovering || dragging;
    }
}
