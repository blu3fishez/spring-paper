package org.blu3fishez.springpaper.examples.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import org.blu3fishez.springpaper.velocity.annotation.VelocityEventListener;
import org.slf4j.Logger;

@VelocityEventListener
public final class PresenceListener {
    private final Logger logger;
    public PresenceListener(Logger logger) { this.logger = logger; }

    @Subscribe public void joined(PostLoginEvent event) { logger.info("Velocity example joined: {}", event.getPlayer().getUsername()); }
}
