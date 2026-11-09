package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a plugin startup callback on the Paper main thread after Spring preparation has completed,
 * before native inputs are registered. Use an ordinary bean relationship only when order matters.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnPaperMainThreadStartup {}
