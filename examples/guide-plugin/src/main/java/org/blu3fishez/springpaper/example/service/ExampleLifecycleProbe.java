package org.blu3fishez.springpaper.example.service;

import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.stereotype.Service;

/** A native smoke probe whose bean name deliberately sorts after its dependent owner. */
@Service("zLifecycleProbe")
@RequiredArgsConstructor
public final class ExampleLifecycleProbe {
    private final JavaPlugin plugin;
    private boolean active;

    @OnPaperMainThreadStartup public void start() {
        requirePaperThread();
        active = true;
        plugin.getLogger().info("SPRING_PAPER_LIFECYCLE_PREPARED");
    }
    @OnPaperMainThreadShutdown public void close() {
        requirePaperThread();
        active = false;
        plugin.getLogger().info("SPRING_PAPER_LIFECYCLE_RELEASED");
    }
    public void requireActive() {
        requirePaperThread();
        if (!active) throw new IllegalStateException("Lifecycle relationship did not keep the probe active");
    }
    private void requirePaperThread() {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Lifecycle left the Paper thread");
    }
}
