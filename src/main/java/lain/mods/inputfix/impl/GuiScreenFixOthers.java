package lain.mods.inputfix.impl;

import com.google.common.base.Strings;
import lain.mods.inputfix.interfaces.IGuiScreen;
import lain.mods.inputfix.interfaces.IGuiScreenFix;
import org.lwjgl.input.Keyboard;

import javax.swing.JOptionPane;

/**
 * 原样移植：Mac/Linux 下 LWJGL 2 无 IME 组合输入，F12（key 88）弹出 Swing 输入框作为回退，
 * 将用户输入逐字符回填 keyTyped。
 */
public class GuiScreenFixOthers implements IGuiScreenFix {

    @Override
    public void handleKeyboardInput(IGuiScreen gui) {
        char c = Keyboard.getEventCharacter();
        int key = Keyboard.getEventKey();
        if (Keyboard.getEventKeyState() || (key == 0 && Character.isDefined(c))) {
            if (key == 88) {
                char[] chars = Strings.nullToEmpty(JOptionPane.showInputDialog("")).toCharArray();
                for (char ch : chars) {
                    gui.keyTyped(ch, 0);
                }
                return;
            }
            gui.keyTyped(c, key);
        }
    }
}
