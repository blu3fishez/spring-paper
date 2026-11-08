package org.blu3fishez.springpaper.jpa;

import org.blu3fishez.springpaper.spi.SpringPaperModule;

public final class JpaModule implements SpringPaperModule {
    @Override public String enabledProperty() { return "spring.paper.jpa.enabled"; }
    @Override public Class<?> configurationClass() { return JpaConfiguration.class; }
}
