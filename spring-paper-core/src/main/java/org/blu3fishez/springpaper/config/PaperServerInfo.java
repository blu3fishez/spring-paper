package org.blu3fishez.springpaper.config;

/** Actual running versions, distinct from the YAML build/development target. */
public record PaperServerInfo(String minecraftVersion, String bukkitVersion,
                              String serverVersion, String javaVersion) {}
