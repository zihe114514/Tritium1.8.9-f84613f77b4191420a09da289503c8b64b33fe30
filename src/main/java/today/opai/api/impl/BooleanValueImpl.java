package today.opai.api.impl;

import today.opai.api.interfaces.modules.values.BooleanValue;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class BooleanValueImpl implements BooleanValue {

    private final String name;
    private boolean value;
    private BooleanSupplier hiddenPredicate = () -> false;
    private Consumer<Boolean> callback = b -> {
    };

    public BooleanValueImpl(String name, boolean defaultValue) {
        this.name = name;
        this.value = defaultValue;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Boolean getValue() {
        return value;
    }

    @Override
    public void setValue(Boolean value) {
        this.value = value;
    }

    @Override
    public void setHiddenPredicate(BooleanSupplier predicate) {
        this.hiddenPredicate = predicate;
    }

    @Override
    public void setValueCallback(Consumer<Boolean> callback) {
        this.callback = callback;
    }

    public boolean isHidden() {
        return hiddenPredicate.getAsBoolean();
    }
}
