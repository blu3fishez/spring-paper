package org.blu3fishez.springpaper.examples.bootvelocity;

import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.blu3fishez.springpaper.velocity.bootstrap.VelocityHostConfiguration;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Found from the root package, with the host's existing objects and typed application settings. */
@Service
public record GreetingService(GatewaySettings settings, ProxyServer proxy, Logger logger,
        @Qualifier(VelocityHostConfiguration.DATA_DIRECTORY_BEAN_NAME) Path dataDirectory) {
    public String greeting() { return settings.greeting(); }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        logger.info("{} (Velocity {}, data: {})", greeting(), proxy.getVersion().getVersion(), dataDirectory);
    }
}
