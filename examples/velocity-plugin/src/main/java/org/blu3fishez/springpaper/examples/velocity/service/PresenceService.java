package org.blu3fishez.springpaper.examples.velocity.service;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public final class PresenceService {
    private final ProxyServer proxy;
    private final Logger logger;

    public PresenceService(ProxyServer proxy, Logger logger) { this.proxy = proxy; this.logger = logger; }
    public int playerCount() { return proxy.getPlayerCount(); }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void reportPresence() { logger.info("Velocity example: {} online players", playerCount()); }
}
