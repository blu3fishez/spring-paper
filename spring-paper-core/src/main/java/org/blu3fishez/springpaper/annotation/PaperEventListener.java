package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/** Marks a component or an existing listener returned by a Spring {@code @Bean} method. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface PaperEventListener {}
