package org.blu3fishez.springpaper.pdc.internal;

import org.blu3fishez.springpaper.spi.SpringPaperModule;

public final class PdcModule implements SpringPaperModule {
    @Override public String enabledProperty() { return "spring.paper.pdc.enabled"; }
    @Override public Class<?> configurationClass() { return PdcConfiguration.class; }
}
