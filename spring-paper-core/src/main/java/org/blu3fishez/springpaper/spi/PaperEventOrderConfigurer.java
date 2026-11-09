package org.blu3fishez.springpaper.spi;

import org.blu3fishez.springpaper.listener.PaperEventOrderRegistry;

/** Consumer composition configures native input relationships; the core calls it once before registration. */
@FunctionalInterface
public interface PaperEventOrderConfigurer {
    void configure(PaperEventOrderRegistry registry);
}
