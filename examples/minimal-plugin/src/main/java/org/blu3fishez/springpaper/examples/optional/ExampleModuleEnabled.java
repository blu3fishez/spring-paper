package org.blu3fishez.springpaper.examples.optional;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Example-owned startup condition; the library does not own application feature flags. */
public final class ExampleModuleEnabled implements Condition {
    public static final String PROPERTY = "example.module.enabled";
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return context.getEnvironment().getProperty(PROPERTY, Boolean.class, false);
    }
}
