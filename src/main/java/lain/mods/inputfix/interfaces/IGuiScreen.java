package lain.mods.inputfix.interfaces;

/**
 * 原样移植：GuiScreen 经 ASM 注入实现此接口，供 IME 桥接层回调 keyTyped。
 */
public interface IGuiScreen {
    void keyTyped(char c, int key);
}
