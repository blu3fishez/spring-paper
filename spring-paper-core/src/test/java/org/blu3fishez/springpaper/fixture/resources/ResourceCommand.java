package org.blu3fishez.springpaper.fixture.resources;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.springframework.stereotype.Component;
import org.springframework.cache.annotation.Cacheable;

@Component
public class ResourceCommand implements CommandExecutor {
    private int invocations;

    @Cacheable(cacheNames = "commands", key = "#p2")
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        invocations++;
        return true;
    }
    public int invocations() { return invocations; }
}
