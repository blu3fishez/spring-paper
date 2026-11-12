package org.blu3fishez.springpaper.fixture.bootlegacy;

import org.blu3fishez.springpaper.spi.SpringPaperModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A consumer-provided SPI remains available only through the legacy carrier. */
public class UserFeatureModule implements SpringPaperModule {
    public static int constructions;
    public static int propertyChecks;
    public static int configurations;

    public UserFeatureModule() { constructions++; }
    @Override public String enabledProperty() { propertyChecks++; return "spring.paper.fixture.enabled"; }
    @Override public Class<?> configurationClass() { configurations++; return Features.class; }

    @Configuration(proxyBeanMethods = false)
    public static class Features {
        @Bean String userFeatureMarker() { return "legacy-user-spi"; }
    }
}
