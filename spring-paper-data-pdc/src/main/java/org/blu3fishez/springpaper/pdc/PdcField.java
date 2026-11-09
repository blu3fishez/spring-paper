package org.blu3fishez.springpaper.pdc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.bukkit.persistence.PersistentDataType;

/** Explicit persisted identity. Renaming a record component never renames its stored key. */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface PdcField {
    String key();
    boolean required() default true;

    /** A Spring bean implementing Paper's native type contract, or automatic built-in mapping. */
    @SuppressWarnings("rawtypes")
    Class<? extends PersistentDataType> type() default PersistentDataType.class;
}
