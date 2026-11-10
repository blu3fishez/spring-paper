package org.blu3fishez.springpaper.config;

import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Legacy Bukkit config.yml persistence; new application settings use Spring Environment/PaperConfiguration. */
public final class PaperBukkitConfiguration {
    private final JavaPlugin plugin;
    public PaperBukkitConfiguration(JavaPlugin plugin) { this.plugin = Objects.requireNonNull(plugin); }
    // Fetch the current object rather than keeping a stale snapshot after a host reload.
    public FileConfiguration values() { return plugin.getConfig(); }
    public void save() { plugin.saveConfig(); }
}
