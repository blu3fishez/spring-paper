package org.blu3fishez.springpaper.example.service;

import java.nio.file.Files;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.blu3fishez.springpaper.bootstrap.PaperApplicationReadyEvent;
import org.blu3fishez.springpaper.example.listener.ExampleSignalEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class ExampleLifecycleService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ExampleLifecycleProbe probe;
    @OnPaperMainThreadStartup public void start() { probe.requireActive(); plugin.getLogger().info("SPRING_PAPER_CUSTOM_START"); }
    @EventListener public void ready(PaperApplicationReadyEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getServer().getPluginManager().callEvent(new ExampleSignalEvent());
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "springpaper");
        });
    }
    // AutoCloseable의 Spring 추론 종료와 겹쳐도 OnPaperMainThreadShutdown 소유자가 한 번만 실행되어야 한다.
    @Override @OnPaperMainThreadShutdown public void close() {
        probe.requireActive();
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(plugin.getDataFolder().toPath().resolve("smoke-stop.txt"), "custom-stop\n");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        plugin.getLogger().info("SPRING_PAPER_CUSTOM_STOP");
    }
}
