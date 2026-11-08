package org.blu3fishez.springpaper.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

/** The consumer's annotated entrypoint can be an empty subclass. */
public abstract class SpringPaperPlugin extends JavaPlugin {
    private SpringPaperApplication application;
    @Override public final void onEnable() { application = SpringPaperApplication.run(this); }
    @Override public final void onDisable() {
        if (application == null) return;
        try { application.close(); } finally { application = null; }
    }
}
