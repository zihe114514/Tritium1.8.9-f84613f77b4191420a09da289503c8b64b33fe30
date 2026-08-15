package today.opai.api.impl;

import today.opai.api.interfaces.modules.values.ColorValue;

import java.awt.Color;
import java.util.function.BooleanSupplier;

public class ColorValueImpl implements ColorValue {

    private final String name;
    private Color value;
    private boolean alphaAllowed;
    private BooleanSupplier hiddenPredicate = () -> false;

    public ColorValueImpl(String name, Color defaultValue) {
        this.name = name;
        this.value = defaultValue;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Color getValue() {
        return value;
    }

    @Override
    public void setValue(Color value) {
        this.value = value;
    }

    @Override
    public void setAlphaAllowed(boolean allowed) {
        this.alphaAllowed = allowed;
    }

    @Override
    public void setHiddenPredicate(BooleanSupplier predicate) {
        this.hiddenPredicate = predicate;
    }

    public boolean isHidden() {
        return hiddenPredicate.getAsBoolean();
    }
}
