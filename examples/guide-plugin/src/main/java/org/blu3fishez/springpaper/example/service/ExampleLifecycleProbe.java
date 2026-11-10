package org.blu3fishez.springpaper.example.service;

import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.springframework.stereotype.Service;

/** A native smoke probe whose bean name deliberately sorts after its dependent owner. */
@Service("zLifecycleProbe")
@RequiredArgsConstructor
public final class ExampleLifecycleProbe {
    private final Server server;
    private final Logger logger;
    private boolean active;

    @OnPaperMainThreadStartup public void start() {
        requirePaperThread();
        active = true;
        logger.info("SPRING_PAPER_LIFECYCLE_PREPARED");
    }
    @OnPaperMainThreadShutdown public void close() {
        requirePaperThread();
        active = false;
        logger.info("SPRING_PAPER_LIFECYCLE_RELEASED");
    }
    public void requireActive() {
        requirePaperThread();
        if (!active) throw new IllegalStateException("Lifecycle relationship did not keep the probe active");
    }
    private void requirePaperThread() {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Lifecycle left the Paper thread");
    }
}
