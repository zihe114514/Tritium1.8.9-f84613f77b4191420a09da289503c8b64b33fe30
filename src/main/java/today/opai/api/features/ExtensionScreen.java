package today.opai.api.features;

import net.minecraft.client.gui.GuiScreen;

/**
 * 对应原项目 today.opai.api.features.ExtensionScreen。
 * 直接以 Minecraft 的 GuiScreen 为基类：原项目的 initGui/onGuiClosed/keyTyped/mouseClicked
 * 与 GuiScreen 一一对应；唯一差异是原项目 drawScreen(int,int) 为两参，
 * 此处需在移植 NCMScreen 时改为 GuiScreen 的三参 drawScreen(int,int,float)（忽略 partialTicks）。
 */
public abstract class ExtensionScreen extends GuiScreen {

    /**
     * GuiScreen 的三参 drawScreen 委托到原项目 Opai 的两参 drawScreen(int,int)。
     * 原项目 NCMScreen / CoverflowOverlay 均覆写两参 drawScreen；此处加一层桥接，
     * 使上层代码无需改动即可保持原签名。
     */
    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawScreen(mouseX, mouseY);
    }

    public void drawScreen(int mouseX, int mouseY) {
        // 原 Opai ExtensionScreen 的两参绘制入口；子类按需覆写。
    }
}
