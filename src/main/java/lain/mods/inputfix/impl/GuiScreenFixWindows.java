package lain.mods.inputfix.impl;

import lain.mods.inputfix.interfaces.IGuiScreen;
import lain.mods.inputfix.interfaces.IGuiScreenFix;
import org.lwjgl.input.Keyboard;

/**
 * 原样移植：Windows 下 IME 提交的多字节字符以「keycode==0 且非按键按下状态」的事件到达，
 * 原版 handleKeyboardInput 只处理 getEventKeyState()==true 的事件，导致中文被丢弃。
 * 此处补上 key==0 且字符已定义的转发，使提交的中文字符进入 GuiScreen.keyTyped。
 */
public class GuiScreenFixWindows implements IGuiScreenFix {

    @Override
    public void handleKeyboardInput(IGuiScreen gui) {
        char c = Keyboard.getEventCharacter();
        int key = Keyboard.getEventKey();
        if (Keyboard.getEventKeyState() || (key == 0 && Character.isDefined(c))) {
            gui.keyTyped(c, key);
        }
    }
}
