package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;

/** Owns shutdown for this bean, including a partially failed start; cleanup must be idempotent. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaperStop {}
