package org.blu3fishez.springpaper.examples.minimal.command;

import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.examples.minimal.service.GreetingService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

@RequiredArgsConstructor
@PaperCommandExecutor(commands = "paper-hello", aliases = "paper-hi", description = "Spring configuration and injection example")
@PaperCommandExecutor(commands = "paper-welcome", description = "A second native command handled by the same bean")
public final class GreetingCommand implements CommandExecutor {
    private final GreetingService greetings;
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendPlainMessage(greetings.greet(sender.getName()));
        return true;
    }
}
