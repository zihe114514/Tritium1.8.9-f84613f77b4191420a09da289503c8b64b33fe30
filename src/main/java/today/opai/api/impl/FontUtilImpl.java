package today.opai.api.impl;

import net.minecraft.client.Minecraft;
import today.opai.api.interfaces.render.Font;
import today.opai.api.interfaces.render.FontUtil;

public class FontUtilImpl implements FontUtil {

    private Font vanillaFont;

    @Override
    public Font getVanillaFont() {
        if (vanillaFont == null) {
            vanillaFont = new VanillaFont(Minecraft.getMinecraft().fontRendererObj);
        }
        return vanillaFont;
    }
}
