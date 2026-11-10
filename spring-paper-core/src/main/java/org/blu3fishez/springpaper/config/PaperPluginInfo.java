package org.blu3fishez.springpaper.config;

import java.nio.file.Path;
import org.bukkit.plugin.Plugin;

/** Immutable plugin identity captured on the Paper thread, without host lifecycle operations. */
public record PaperPluginInfo(String name, String namespace, Path dataDirectory, ClassLoader classLoader) {
    public static PaperPluginInfo capture(Plugin plugin) {
        return new PaperPluginInfo(plugin.getName(), plugin.namespace(), plugin.getDataFolder().toPath(),
                plugin.getClass().getClassLoader());
    }
}
