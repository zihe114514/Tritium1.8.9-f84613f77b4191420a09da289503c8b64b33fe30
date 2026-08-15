package lain.mods.inputfix;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * InputFix 1.8.x-v2（作者 LainMI / zlainsama，MMPL 许可）。
 * Forge 1.8.9 CoreMod 入口：通过 MANIFEST 的 {@code FMLCorePlugin: lain.mods.inputfix.InputFix} 加载。
 * 原样移植，仅负责中文/多字节 IME 输入兼容，不改动任何搜索/业务逻辑。
 */
public class InputFix implements IFMLLoadingPlugin {

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "lain.mods.inputfix.InputFixTransformer" };
    }

    @Override
    public String getModContainerClass() {
        return "lain.mods.inputfix.InputFixDummyContainer";
    }

    @Override
    public String getSetupClass() {
        return "lain.mods.inputfix.InputFixSetup";
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }
}
