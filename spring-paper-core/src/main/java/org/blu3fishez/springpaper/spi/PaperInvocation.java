package org.blu3fishez.springpaper.spi;

import org.bukkit.command.CommandSender;

/** Caller may be null for events without an identifiable sender. Source is the command/event. */
public record PaperInvocation(CommandSender caller, Object source, boolean asynchronous, Kind kind) {
    public enum Kind { COMMAND, COMPLETION, EVENT }
}
