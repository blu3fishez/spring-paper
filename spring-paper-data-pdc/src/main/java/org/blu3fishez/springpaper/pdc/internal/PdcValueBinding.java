package org.blu3fishez.springpaper.pdc.internal;

import java.util.function.Function;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import io.papermc.paper.persistence.PersistentDataContainerView;

/** Internal conversion; custom implementations use Paper's PersistentDataType SPI directly. */
public record PdcValueBinding<S, T>(PersistentDataType<?, S> storage, Function<S, T> decode, Function<T, S> encode) {
    public Object read(PersistentDataContainerView data, NamespacedKey key) {
        if (!data.has(key, storage)) throw new StoredTypeMismatch();
        S value = data.get(key, storage);
        if (value == null) throw new IllegalArgumentException("PDC type returned a null value");
        return decode.apply(value);
    }
    @SuppressWarnings("unchecked")
    public void write(PersistentDataContainer data, NamespacedKey key, Object value) {
        data.set(key, storage, encode.apply((T) value));
    }
    public static <T> PdcValueBinding<T, T> direct(PersistentDataType<?, T> type) {
        return new PdcValueBinding<>(type, Function.identity(), Function.identity());
    }
    public static final class StoredTypeMismatch extends IllegalArgumentException {
        StoredTypeMismatch() { super("Stored value has the wrong PDC primitive type"); }
    }
}
