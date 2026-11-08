package org.blu3fishez.springpaper.security.spi;

import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/** Consumer transport policy: admission runs on the login thread, revocation on the Paper main thread. */
public interface PaperConnectionAdmissionHandler {
    void admit(AsyncPlayerPreLoginEvent event, PaperSecuritySession session);
    /** A custom pending-world flow must enforce native gameplay restrictions itself. */
    void revoke(Player player, String reason);
}
