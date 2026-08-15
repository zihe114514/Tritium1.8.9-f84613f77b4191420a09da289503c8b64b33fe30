package today.opai.api.interfaces.modules.values;

import java.util.function.Consumer;

public interface BooleanValue extends Value<Boolean> {

    void setValueCallback(Consumer<Boolean> callback);
}
