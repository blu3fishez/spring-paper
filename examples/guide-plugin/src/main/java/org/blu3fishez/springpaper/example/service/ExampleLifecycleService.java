package org.blu3fishez.springpaper.example.service;

import java.nio.file.Files;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.blu3fishez.springpaper.bootstrap.PaperApplicationReadyEvent;
import org.blu3fishez.springpaper.example.listener.ExampleSignalEvent;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.blu3fishez.springpaper.config.PaperPluginInfo;
import org.blu3fishez.springpaper.task.PaperTickScheduler;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class ExampleLifecycleService implements AutoCloseable {
    private final PaperPluginInfo plugin;
    private final Server server;
    private final Logger logger;
    private final PaperTickScheduler ticks;
    private final ExampleLifecycleProbe probe;
    @OnPaperMainThreadStartup public void start() { probe.requireActive(); logger.info("SPRING_PAPER_CUSTOM_START"); }
    @EventListener public void ready(PaperApplicationReadyEvent event) {
        ticks.execute( () -> {
            server.getPluginManager().callEvent(new ExampleSignalEvent());
            server.dispatchCommand(server.getConsoleSender(), "springpaper");
        });
    }
    // AutoCloseable의 Spring 추론 종료와 겹쳐도 OnPaperMainThreadShutdown 소유자가 한 번만 실행되어야 한다.
    @Override @OnPaperMainThreadShutdown public void close() {
        probe.requireActive();
        try {
            Files.createDirectories(plugin.dataDirectory());
            Files.writeString(plugin.dataDirectory().resolve("smoke-stop.txt"), "custom-stop\n");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        logger.info("SPRING_PAPER_CUSTOM_STOP");
    }
}
