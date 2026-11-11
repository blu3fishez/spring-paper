package org.blu3fishez.springpaper.examples.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.blu3fishez.springpaper.velocity.bootstrap.SpringVelocityApplication;
import org.slf4j.Logger;

@Plugin(id = "spring-paper-velocity-example", name = "Spring Velocity Example", version = "0.1.0-SNAPSHOT")
public final class VelocityExamplePlugin {
    private final SpringVelocityApplication application;

    @Inject
    public VelocityExamplePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        application = new SpringVelocityApplication(this, proxy, logger, directory, VelocityExampleConfiguration.class);
    }

    @Subscribe public EventTask initialize(ProxyInitializeEvent event) { return application.initialize(); }
    @Subscribe public EventTask shutdown(ProxyShutdownEvent event) { return application.shutdown(); }
}
