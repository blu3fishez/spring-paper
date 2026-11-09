package org.blu3fishez.springpaper.pdc.internal;

import java.util.Map;
import java.util.UUID;
import org.blu3fishez.springpaper.pdc.PdcField;
import org.bukkit.persistence.PersistentDataType;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.util.ClassUtils;

/** Resolved once per mapping; UUID keeps the common STRING representation, not a new binary format. */
public final class PdcTypes {
    private PdcTypes() {}

    private static final Map<Class<?>, PersistentDataType<?, ?>> NATIVE = Map.ofEntries(
            Map.entry(Byte.class, PersistentDataType.BYTE), Map.entry(Short.class, PersistentDataType.SHORT),
            Map.entry(Integer.class, PersistentDataType.INTEGER), Map.entry(Long.class, PersistentDataType.LONG),
            Map.entry(Float.class, PersistentDataType.FLOAT), Map.entry(Double.class, PersistentDataType.DOUBLE),
            Map.entry(Boolean.class, PersistentDataType.BOOLEAN), Map.entry(String.class, PersistentDataType.STRING),
            Map.entry(byte[].class, PersistentDataType.BYTE_ARRAY), Map.entry(int[].class, PersistentDataType.INTEGER_ARRAY),
            Map.entry(long[].class, PersistentDataType.LONG_ARRAY));

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static PdcValueBinding<?, ?> resolve(Class<?> component, PdcField field, BeanFactory beans) {
        Class<?> type = ClassUtils.resolvePrimitiveIfNecessary(component);
        if (field.type() != PersistentDataType.class) {
            PersistentDataType<?, ?> custom = beans.getBean(field.type());
            if (custom.getComplexType() != type) throw new IllegalArgumentException("Custom PDC type must produce " + type.getName());
            return PdcValueBinding.direct(custom);
        }
        if (type == UUID.class) {
            return new PdcValueBinding<>(PersistentDataType.STRING, UUID::fromString, UUID::toString);
        }
        if (type.isEnum()) {
            return new PdcValueBinding<String, Enum>(PersistentDataType.STRING,
                    value -> Enum.valueOf((Class) type, value), Enum::name);
        }
        PersistentDataType<?, ?> nativeType = NATIVE.get(type);
        if (nativeType == null) throw new IllegalArgumentException("Declare a PersistentDataType bean for " + type.getName());
        return PdcValueBinding.direct(nativeType);
    }
}
