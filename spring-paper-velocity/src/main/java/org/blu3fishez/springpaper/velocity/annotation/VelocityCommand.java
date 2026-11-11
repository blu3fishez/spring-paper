package org.blu3fishez.springpaper.velocity.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;

/** Binds one native Velocity Command bean to its canonical alias and optional secondary aliases. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface VelocityCommand {
    @AliasFor(annotation = Component.class, attribute = "value")
    String value();
    String[] aliases() default {};
}
