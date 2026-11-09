package org.blu3fishez.springpaper.pdc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Storage mapping metadata for an immutable record. No keys or schema versions are added implicitly. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PdcDocument {
    /** Component whose key identifies a tagged holder. Empty means any mapped key. */
    String marker() default "";
}
