package org.blu3fishez.springpaper.jdbc;

import org.blu3fishez.springpaper.spi.SpringPaperModule;

/** Standalone JDBC support; no JPA, pool creation, or schema migration dependency. */
public final class JdbcModule implements SpringPaperModule {
    @Override public String enabledProperty() { return "spring.paper.jdbc.enabled"; }
    @Override public Class<?> configurationClass() { return JdbcConfiguration.class; }
}
