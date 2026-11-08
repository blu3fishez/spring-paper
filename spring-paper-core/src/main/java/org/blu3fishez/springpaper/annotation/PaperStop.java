package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;

/** Owns shutdown for this bean, including a partially failed start; cleanup must be idempotent. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaperStop {
    /** Higher phases stop first. The default inherits PaperStart's phase and reverses actual start order. */
    int phase() default Integer.MIN_VALUE;
    /** Integer or environment placeholder for an independently configured shutdown order. */
    String phaseString() default "";
}
