package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface PaperEventListener {}
