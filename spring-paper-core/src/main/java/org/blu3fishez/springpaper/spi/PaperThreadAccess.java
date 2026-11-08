package org.blu3fishez.springpaper.spi;

/** Host thread query supplied by the core; integrations need no direct Bukkit dependency. */
@FunctionalInterface
public interface PaperThreadAccess { boolean isPrimaryThread(); }
