package org.blu3fishez.springpaper.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

/** The consumer's annotated entrypoint can be an empty subclass. */
public abstract class SpringPaperPlugin extends JavaPlugin {
    private SpringPaperApplication application;
    @Override public final void onEnable() {
        try { application = SpringPaperApplication.run(this); }
        catch (RuntimeException | Error failure) {
            try { onApplicationStartupFailure(failure); }
            catch (Throwable policyFailure) { if (policyFailure != failure) failure.addSuppressed(policyFailure); }
            throw failure;
        }
    }
    /** Consumer-owned host policy after partial application cleanup. The library never stops the server itself. */
    protected void onApplicationStartupFailure(Throwable failure) {}
    @Override public final void onDisable() {
        if (application == null) return;
        try { application.close(); } finally { application = null; }
    }
}
