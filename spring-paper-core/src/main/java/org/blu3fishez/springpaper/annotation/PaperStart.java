package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;

/** A public no-arg main-thread hook. Lower phases start first and stop last. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaperStart { int phase() default 0; }
