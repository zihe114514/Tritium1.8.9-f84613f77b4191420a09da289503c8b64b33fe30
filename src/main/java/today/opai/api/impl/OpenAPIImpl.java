package today.opai.api.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import today.opai.api.OpenAPI;
import today.opai.api.features.ExtensionModule;
import today.opai.api.features.ExtensionScreen;
import today.opai.api.features.ExtensionWidget;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.modules.values.ValueManager;
import today.opai.api.interfaces.render.FontUtil;
import today.opai.api.interfaces.render.GLStateManager;
import today.opai.api.interfaces.render.RenderUtil;
import today.opai.api.interfaces.render.ShaderUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OpenAPIImpl extends OpenAPI {

    private final List<EventHandler> eventHandlers = new ArrayList<>();
    private final List<ExtensionModule> modules = new ArrayList<>();
    private final List<ExtensionWidget> widgets = new ArrayList<>();

    private final GLStateManager glStateManager = new GLStateManagerImpl();
    private final ValueManager valueManager = new ValueManagerImpl();
    private final FontUtil fontUtil = new FontUtilImpl();
    private final ShaderUtil shaderUtil = new ShaderUtilImpl();
    private final RenderUtil renderUtil = new RenderUtilImpl();

    @Override
    public void registerEvent(EventHandler handler) {
        if (!eventHandlers.contains(handler)) {
            eventHandlers.add(handler);
        }
    }

    @Override
    public void unregisterEvent(EventHandler handler) {
        eventHandlers.remove(handler);
    }

    @Override
    public void registerFeature(Object feature) {
        if (feature instanceof ExtensionModule) {
            modules.add((ExtensionModule) feature);
        } else if (feature instanceof ExtensionWidget) {
            widgets.add((ExtensionWidget) feature);
        }
    }

    @Override
    public void displayScreen(ExtensionScreen screen) {
        Minecraft.getMinecraft().displayGuiScreen(screen);
    }

    @Override
    public void printMessage(String message) {
        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message));
    }

    @Override
    public ValueManager getValueManager() {
        return valueManager;
    }

    @Override
    public GLStateManager getGLStateManager() {
        return glStateManager;
    }

    @Override
    public ShaderUtil getShaderUtil() {
        return shaderUtil;
    }

    @Override
    public FontUtil getFontUtil() {
        return fontUtil;
    }

    @Override
    public RenderUtil getRenderUtil() {
        return renderUtil;
    }

    // ---- Forge 事件处理器在 S4/S5 会使用以下访问器 ----

    public List<EventHandler> getEventHandlers() {
        return Collections.unmodifiableList(eventHandlers);
    }

    public List<ExtensionModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<ExtensionWidget> getWidgets() {
        return Collections.unmodifiableList(widgets);
    }
}
