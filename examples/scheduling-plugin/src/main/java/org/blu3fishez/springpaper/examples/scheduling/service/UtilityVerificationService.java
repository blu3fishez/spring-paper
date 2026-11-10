package org.blu3fishez.springpaper.examples.scheduling.service;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.config.PaperBukkitConfiguration;
import org.blu3fishez.springpaper.config.PaperPluginInfo;
import org.blu3fishez.springpaper.key.PaperKeys;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.blu3fishez.springpaper.world.PaperChunkTickets;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class UtilityVerificationService {
    private final Server server;
    private final PaperKeys keys;
    private final PaperPluginInfo plugin;
    private final PaperBukkitConfiguration legacy;
    private final PaperChunkTickets tickets;
    private final PaperTasks tasks;

    private CompletableFuture<String> verification;

    public CompletableFuture<String> verify() {
        if (verification != null) return verification.copy();
        String key = keys.key("Example/Identity").toString();
        if (!key.equals(plugin.namespace() + ":example/identity")) throw new IllegalStateException("Key namespace changed");
        if (plugin.classLoader().getResource("application.yml") == null) throw new IllegalStateException("Plugin resource missing");
        var chunk = server.getWorlds().getFirst().getSpawnLocation().getChunk();
        try (var first = tickets.acquire(chunk); var second = tickets.acquire(chunk)) {
            // Both users retain one native plugin ticket until the last lease closes.
        }
        legacy.values().set("example.legacy-roundtrip", true);
        verification = tasks.async(() -> {
            legacy.save();
            var persisted = YamlConfiguration.loadConfiguration(plugin.dataDirectory().resolve("config.yml").toFile());
            if (!persisted.getBoolean("example.legacy-roundtrip")) throw new IllegalStateException("Legacy save was not persisted");
            return "UTILITIES_OK key=" + key + " metadata=host config=persisted leases=closed";
        });
        return verification.copy();
    }
}
