package tritium.utils;

import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/8/2 10:03
 */
public class Lazy<T> implements Supplier<T> {

    private final Supplier<T> supplier;
    private T value;

    public Lazy(final Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public T get() {
        synchronized (this) {
            if (value == null) {
                value = supplier.get();
            }

            return value;
        }
    }

    public T getValue() {
        return this.get();
    }

    public static <T> Lazy<T> of(final Supplier<T> supplier) {
        return new Lazy<>(supplier);
    }

}
