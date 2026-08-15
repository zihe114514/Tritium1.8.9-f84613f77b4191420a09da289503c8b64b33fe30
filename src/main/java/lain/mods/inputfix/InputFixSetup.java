package lain.mods.inputfix;

import lain.mods.inputfix.impl.GuiScreenFixOthers;
import lain.mods.inputfix.impl.GuiScreenFixWindows;
import lain.mods.inputfix.interfaces.IGuiScreenFix;
import lain.mods.inputfix.utils.OSDetector;
import net.minecraftforge.fml.relauncher.IFMLCallHook;

import java.util.Map;

/**
 * 原样移植：按操作系统选择 IME 实现。
 * Windows 走 {@link GuiScreenFixWindows}；Linux/Mac 走 {@link GuiScreenFixOthers}（F12 弹窗回退）。
 */
public class InputFixSetup implements IFMLCallHook {

    public static IGuiScreenFix impl;

    @Override
    public Void call() throws Exception {
        OSDetector.OS os = OSDetector.detectOS();
        switch (os) {
            case Windows:
                impl = new GuiScreenFixWindows();
                break;
            case Linux:
            case Mac:
                try {
                    impl = new GuiScreenFixOthers();
                } catch (Throwable t) {
                    impl = new GuiScreenFixWindows();
                }
                break;
            default:
                break;
        }
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }
}
