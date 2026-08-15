package today.opai.api.impl;

import today.opai.api.interfaces.modules.values.BooleanValue;
import today.opai.api.interfaces.modules.values.ColorValue;
import today.opai.api.interfaces.modules.values.ModeValue;
import today.opai.api.interfaces.modules.values.NumberValue;
import today.opai.api.interfaces.modules.values.ValueManager;

import java.awt.Color;

public class ValueManagerImpl implements ValueManager {

    @Override
    public BooleanValue createBoolean(String name, boolean defaultValue) {
        return new BooleanValueImpl(name, defaultValue);
    }

    @Override
    public ModeValue createModes(String name, String defaultValue, String[] modes) {
        return new ModeValueImpl(name, defaultValue, modes);
    }

    @Override
    public NumberValue createDouble(String name, double defaultValue, double min, double max, double step) {
        return new NumberValueImpl(name, defaultValue, min, max, step);
    }

    @Override
    public ColorValue createColor(String name, Color defaultValue) {
        return new ColorValueImpl(name, defaultValue);
    }
}
