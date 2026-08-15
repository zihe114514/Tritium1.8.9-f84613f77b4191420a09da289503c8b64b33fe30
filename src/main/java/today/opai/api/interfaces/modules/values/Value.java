package today.opai.api.interfaces.modules.values;

import java.util.function.BooleanSupplier;

/**
 * 对应原项目 modules.values.Value 的公共基接口。
 */
public interface Value<T> {

    String getName();

    T getValue();

    void setValue(T value);

    void setHiddenPredicate(BooleanSupplier predicate);
}
