package org.blu3fishez.springpaper.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.ApplicationEvent;

/** Published on the main thread after custom starts and input registrations are complete. */
public final class PaperApplicationReadyEvent extends ApplicationEvent {
    public PaperApplicationReadyEvent(JavaPlugin plugin) { super(plugin); }
    public JavaPlugin plugin() { return (JavaPlugin) getSource(); }
}
