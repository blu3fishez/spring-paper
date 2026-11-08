package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;

/** A public no-arg main-thread hook. Lower phases start first; stop can override its phase independently. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaperStart {
    int phase() default 0;
    /** Integer or environment placeholder for role-dependent ordering; mutually exclusive with a nondefault phase. */
    String phaseString() default "";
}
