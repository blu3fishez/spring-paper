package org.blu3fishez.springpaper.security.service;

import net.kyori.adventure.text.Component;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.blu3fishez.springpaper.security.spi.PaperConnectionAdmissionHandler;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/** Default: only fully authenticated sessions enter the world. Does not overwrite another plugin's denial. */
public final class DefaultPaperConnectionAdmissionHandler implements PaperConnectionAdmissionHandler {
    private final String message;
    public DefaultPaperConnectionAdmissionHandler(String message) { this.message = message; }
    @Override public void admit(AsyncPlayerPreLoginEvent event, PaperSecuritySession session) {
        if (session.status() != PaperSecuritySession.Status.AUTHENTICATED) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(message));
        }
    }
    @Override public void revoke(Player player, String reason) { player.kick(Component.text(message)); }
}
