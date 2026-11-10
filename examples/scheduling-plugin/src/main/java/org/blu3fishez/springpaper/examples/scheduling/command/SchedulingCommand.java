package org.blu3fishez.springpaper.examples.scheduling.command;

import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.examples.scheduling.service.SchedulingProbeService;
import org.blu3fishez.springpaper.examples.scheduling.service.UtilityVerificationService;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

@RequiredArgsConstructor
@PaperCommandExecutor(commands = "paper-scheduling", permission = "springpaper.scheduling", description = "Verify scheduling and host utilities")
public final class SchedulingCommand implements CommandExecutor {
    private final SchedulingProbeService scheduling;
    private final org.blu3fishez.springpaper.examples.scheduling.service.AsyncProbeService async;
    private final UtilityVerificationService utilities;
    private final PaperTasks tasks;
    private final Logger logger;
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String scheduled = scheduling.verify();
        utilities.verify().thenCombine(async.verify(), (utilities, async) -> utilities + "\n" + async)
                .thenCompose(result -> tasks.main(() -> sender.sendPlainMessage(scheduled + "\n" + result)))
                .exceptionally(error -> { logger.log(Level.SEVERE, "Scheduling example failed", error); return null; });
        return true;
    }
}
