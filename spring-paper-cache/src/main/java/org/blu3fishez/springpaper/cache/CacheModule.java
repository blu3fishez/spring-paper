package org.blu3fishez.springpaper.cache;

import org.blu3fishez.springpaper.spi.SpringPaperModule;

public final class CacheModule implements SpringPaperModule {
    @Override public String enabledProperty() { return "spring.paper.cache.enabled"; }
    @Override public Class<?> configurationClass() { return CacheConfiguration.class; }
}
