package org.blu3fishez.springpaper.security.spi;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.springframework.security.core.Authentication;

/** Extract local connection evidence promptly. Providers/async filters perform the actual verification. */
@FunctionalInterface
public interface PaperConnectionAuthenticationConverter {
    Authentication convert(AsyncPlayerPreLoginEvent event);
}
