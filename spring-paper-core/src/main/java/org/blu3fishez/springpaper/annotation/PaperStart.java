package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;

/** @deprecated Use {@link OnPaperMainThreadStartup} and semantic lifecycle relationships. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Deprecated
public @interface PaperStart {
    int phase() default 0;
    /** Integer or environment placeholder for role-dependent ordering; mutually exclusive with a nondefault phase. */
    String phaseString() default "";
}
