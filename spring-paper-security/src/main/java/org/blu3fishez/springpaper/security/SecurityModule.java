package org.blu3fishez.springpaper.security;

import org.blu3fishez.springpaper.spi.SpringPaperModule;

public final class SecurityModule implements SpringPaperModule {
    @Override public String enabledProperty() { return "spring.paper.security.enabled"; }
    @Override public Class<?> configurationClass() { return SecurityConfiguration.class; }
}
