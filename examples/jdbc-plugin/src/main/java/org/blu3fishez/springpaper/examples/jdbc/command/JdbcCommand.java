package org.blu3fishez.springpaper.examples.jdbc.command;

import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.examples.jdbc.service.JdbcVerificationService;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

@RequiredArgsConstructor
@PaperCommandExecutor(commands = "paper-jdbc", permission = "springpaper.jdbc", description = "Verify standalone Spring JDBC transactions")
public final class JdbcCommand implements CommandExecutor {
    private final JdbcVerificationService verification;
    private final PaperTasks tasks;
    private final Logger logger;
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        tasks.async(verification::verify).thenCompose(result -> tasks.main(() -> sender.sendPlainMessage(result)))
                .exceptionally(error -> { logger.log(Level.SEVERE, "JDBC example failed", error); return null; });
        return true;
    }
}
