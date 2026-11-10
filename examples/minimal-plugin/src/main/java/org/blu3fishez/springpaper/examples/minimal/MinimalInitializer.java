package org.blu3fishez.springpaper.examples.minimal;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Standard Spring initialization, before bean discovery and refresh. */
public final class MinimalInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("example-initializer", Map.of("example.initialized", true)));
    }
}
