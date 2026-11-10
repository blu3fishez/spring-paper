package org.blu3fishez.springpaper.examples.optional;

import org.blu3fishez.springpaper.spi.SpringPaperModule;

/** Provider-owned activation; keep this configuration outside the application's unconditional scan. */
public final class OptionalExampleModule implements SpringPaperModule {
    @Override public String enabledProperty() { return ExampleModuleEnabled.PROPERTY; }
    @Override public Class<?> configurationClass() { return OptionalModuleConfiguration.class; }
}
