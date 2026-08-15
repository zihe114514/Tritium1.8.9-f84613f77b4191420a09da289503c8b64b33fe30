package lain.mods.inputfix;

import lain.mods.inputfix.interfaces.IGuiScreen;
import lain.mods.inputfix.utils.ReflectionHelper;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Method;

/**
 * 原样移植：被 ASM 注入进 GuiScreen.handleKeyboardInput 后的静态桥接。
 * 通过反射（SRG/MCP 双候选名）调用 GuiScreen.keyTyped，实现虚拟分发到各子类覆写。
 */
public class GuiScreenFix {

    private static final ThreadLocal<Proxy> proxies = new ThreadLocal<Proxy>() {
        @Override
        protected Proxy initialValue() {
            return new Proxy();
        }
    };

    private static final Method keyTyped;

    static {
        keyTyped = ReflectionHelper.findMethod(
                GuiScreen.class,
                new String[] { "func_73869_a", "keyTyped" },
                new Class[] { char.class, int.class }
        );
    }

    public static void handleKeyboardInput(GuiScreen gui) {
        Proxy proxy = proxies.get().setGui(gui);
        if (InputFixSetup.impl != null) {
            InputFixSetup.impl.handleKeyboardInput(proxy);
        } else {
            if (Keyboard.getEventKeyState()) {
                proxy.keyTyped(Keyboard.getEventCharacter(), Keyboard.getEventKey());
            }
        }
        gui.mc.dispatchKeypresses();
    }

    static Method access$000() {
        return keyTyped;
    }

    private static class Proxy implements IGuiScreen {

        private GuiScreen gui;

        private Proxy() {
        }

        @Override
        public void keyTyped(char c, int key) {
            if (gui != null) {
                try {
                    GuiScreenFix.access$000().invoke(gui, Character.valueOf(c), Integer.valueOf(key));
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
        }

        private Proxy setGui(GuiScreen gui) {
            this.gui = gui;
            return this;
        }
    }
}
