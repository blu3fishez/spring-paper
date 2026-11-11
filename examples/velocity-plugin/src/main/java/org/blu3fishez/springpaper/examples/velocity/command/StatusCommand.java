package org.blu3fishez.springpaper.examples.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import org.blu3fishez.springpaper.examples.velocity.service.PresenceService;
import org.blu3fishez.springpaper.velocity.annotation.VelocityCommand;

@VelocityCommand(value = "spring-velocity", aliases = "sv")
public final class StatusCommand implements SimpleCommand {
    private final PresenceService presence;
    public StatusCommand(PresenceService presence) { this.presence = presence; }

    @Override public void execute(Invocation invocation) {
        invocation.source().sendMessage(Component.text("Velocity example: " + presence.playerCount() + " online players"));
    }
}
