package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;

/** @deprecated Use {@link OnPaperMainThreadShutdown} and semantic lifecycle relationships. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Deprecated
public @interface PaperStop {
    /** Higher phases stop first. The default inherits PaperStart's phase and reverses actual start order. */
    int phase() default Integer.MIN_VALUE;
    /** Integer or environment placeholder for an independently configured shutdown order. */
    String phaseString() default "";
}
