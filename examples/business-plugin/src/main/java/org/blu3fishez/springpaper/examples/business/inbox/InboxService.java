package org.blu3fishez.springpaper.examples.business.inbox;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@RequiredArgsConstructor
public final class InboxService {
    private final InboxRepository repository;
    private final PaperTasks execution;
    private final Server server;
    private final Logger logger;

    public CompletableFuture<Void> show(CommandSender sender) {
        UUID owner = sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0);
        var result = execution.nextTick(execution.async(() -> repository.pending(owner)), (count, error) -> {
            if (sender instanceof Player player
                    && (!player.isOnline() || server.getPlayer(owner) != player)) return;
            if (error != null) throw new CompletionException(error);
            sender.sendPlainMessage("Pending mail: " + count);
        });
        result.whenComplete((ignored, error) -> {
            if (error != null) logger.log(Level.WARNING, "Inbox lookup/apply was not completed", error);
        });
        return result;
    }
}
