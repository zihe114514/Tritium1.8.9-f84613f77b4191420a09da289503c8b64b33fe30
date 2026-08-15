package today.opai.api.interfaces.modules.values;

import java.util.function.Consumer;

public interface NumberValue extends Value<Double> {

    void setValueCallback(Consumer<Number> callback);
}
