package today.opai.api.impl;

import today.opai.api.interfaces.modules.values.NumberValue;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class NumberValueImpl implements NumberValue {

    private final String name;
    private final double min;
    private final double max;
    private double value;
    private BooleanSupplier hiddenPredicate = () -> false;
    private Consumer<Number> callback = v -> {
    };

    public NumberValueImpl(String name, double defaultValue, double min, double max, double step) {
        this.name = name;
        this.value = defaultValue;
        this.min = min;
        this.max = max;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Double getValue() {
        return value;
    }

    @Override
    public void setValue(Double value) {
        this.value = value;
        // 移植修复：原 Opai 框架的 setValue 会触发 setValueCallback 注册的回调；
        // 此处回调曾只存值不触发，导致 MusicInfoWidget.volume 的回调（调 player.setVolume）永不执行，
        // 歌词页音量条 setValue 后无法真正调节播放器音量。
        this.callback.accept(value);
    }

    @Override
    public void setHiddenPredicate(BooleanSupplier predicate) {
        this.hiddenPredicate = predicate;
    }

    @Override
    public void setValueCallback(Consumer<Number> callback) {
        this.callback = callback;
    }

    public boolean isHidden() {
        return hiddenPredicate.getAsBoolean();
    }
}
