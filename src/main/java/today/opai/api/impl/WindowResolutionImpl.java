package today.opai.api.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import today.opai.api.interfaces.render.WindowResolution;

public class WindowResolutionImpl implements WindowResolution {

    private final ScaledResolution resolution;

    public WindowResolutionImpl() {
        this.resolution = new ScaledResolution(Minecraft.getMinecraft());
    }

    @Override
    public int getScaleFactor() {
        return resolution.getScaleFactor();
    }

    @Override
    public int getWidth() {
        return resolution.getScaledWidth();
    }

    @Override
    public int getHeight() {
        return resolution.getScaledHeight();
    }
}
