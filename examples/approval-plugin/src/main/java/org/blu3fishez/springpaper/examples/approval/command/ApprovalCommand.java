package org.blu3fishez.springpaper.examples.approval.command;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.examples.approval.service.ApprovalAdministrationService;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@RequiredArgsConstructor
@PaperCommandExecutor(commands = "paper-approval", permission = "springpaper.approval", usage = "/paper-approval verify|approve <uuid>")
public final class ApprovalCommand implements CommandExecutor {
    private final ApprovalAdministrationService administration;
    private final PaperTasks tasks;
    private final Logger logger;
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) { sender.sendPlainMessage("서버 콘솔에서 실행해 주세요."); return true; }
        if (args.length == 2 && args[0].equals("approve")) {
            administration.approve(UUID.fromString(args[1]));
            sender.sendPlainMessage("승인했습니다. 해당 플레이어가 다시 접속하면 적용됩니다.");
        } else if (args.length == 1 && args[0].equals("verify")) {
            administration.verify().thenCompose(result -> tasks.main(() -> sender.sendPlainMessage(result)))
                    .exceptionally(error -> { logger.log(Level.SEVERE, "Approval example failed", error); return null; });
        } else return false;
        return true;
    }
}
