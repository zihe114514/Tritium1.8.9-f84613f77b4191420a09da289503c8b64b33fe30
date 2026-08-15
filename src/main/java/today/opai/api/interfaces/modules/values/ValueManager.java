package today.opai.api.interfaces.modules.values;

import java.awt.Color;

public interface ValueManager {

    BooleanValue createBoolean(String name, boolean defaultValue);

    ModeValue createModes(String name, String defaultValue, String[] modes);

    NumberValue createDouble(String name, double defaultValue, double min, double max, double step);

    ColorValue createColor(String name, Color defaultValue);
}
