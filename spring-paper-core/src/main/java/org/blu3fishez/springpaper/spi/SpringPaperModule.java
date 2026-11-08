package org.blu3fishez.springpaper.spi;

/** Implemented by optional integrations; called by the core before context refresh. No I/O here. */
public interface SpringPaperModule {
    String enabledProperty();
    Class<?> configurationClass();
}
