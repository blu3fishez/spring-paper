package org.blu3fishez.springpaper.bootstrap;

import org.blu3fishez.springpaper.boot.ApplicationRootMetadata;
import org.bukkit.plugin.java.JavaPlugin;

/** Native descriptor entrypoint for ordinary application roots, shaded into each consumer artifact. */
public final class SpringPaperApplicationPlugin extends JavaPlugin {
    private SpringPaperApplication application;

    @Override public void onEnable() {
        Class<?> root = ApplicationRootMetadata.load(getClass(), "paper");
        application = SpringPaperApplication.run(this, root);
    }

    @Override public void onDisable() {
        if (application == null) return;
        try { application.close(); }
        finally { application = null; }
    }
}
