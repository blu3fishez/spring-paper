package org.blu3fishez.springpaper.pdc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a consumer interface extending {@link PdcRepository}; imported by an explicit repository scan. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaperPdcRepository {}
