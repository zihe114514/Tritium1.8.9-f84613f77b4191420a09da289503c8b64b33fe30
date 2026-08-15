package today.opai.api.impl;

import today.opai.api.interfaces.modules.values.ModeValue;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ModeValueImpl implements ModeValue {

    private final String name;
    private final String[] modes;
    private String value;
    private BooleanSupplier hiddenPredicate = () -> false;
    private Consumer<String> callback = s -> {
    };

    public ModeValueImpl(String name, String defaultValue, String[] modes) {
        this.name = name;
        this.value = defaultValue;
        this.modes = modes;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public void setHiddenPredicate(BooleanSupplier predicate) {
        this.hiddenPredicate = predicate;
    }

    @Override
    public void setValueCallback(Consumer<String> callback) {
        this.callback = callback;
    }

    public boolean isHidden() {
        return hiddenPredicate.getAsBoolean();
    }
}
