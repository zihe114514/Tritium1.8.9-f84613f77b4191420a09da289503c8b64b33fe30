package today.opai.api.interfaces.modules.values;

import java.util.function.Consumer;

public interface ModeValue extends Value<String> {

    void setValueCallback(Consumer<String> callback);
}
