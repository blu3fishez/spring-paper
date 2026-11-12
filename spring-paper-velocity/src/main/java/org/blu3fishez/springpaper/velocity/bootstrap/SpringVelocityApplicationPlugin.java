package org.blu3fishez.springpaper.velocity.bootstrap;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.blu3fishez.springpaper.boot.ApplicationRootMetadata;
import org.slf4j.Logger;

/** Native Guice entry point shared by flat application JARs; each reads only its own root metadata. */
public final class SpringVelocityApplicationPlugin {
    private final SpringVelocityApplication application;

    @Inject
    public SpringVelocityApplicationPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        Class<?> bridge = getClass();
        Class<?> root = ApplicationRootMetadata.load(bridge, "velocity");
        if (!root.isAnnotationPresent(org.blu3fishez.springpaper.velocity.annotation.SpringVelocityApplication.class)) {
            throw new IllegalArgumentException("Velocity root requires @SpringVelocityApplication: " + root.getName());
        }
        if (root.isAnnotationPresent(com.velocitypowered.api.plugin.Plugin.class)) {
            throw new IllegalArgumentException("Ordinary Velocity root must be owned only by Spring: " + root.getName());
        }
        application = new SpringVelocityApplication(this, proxy, logger, directory, root,
                ApplicationRootMetadata.artifact(bridge));
    }

    @Subscribe public EventTask initialize(ProxyInitializeEvent event) { return application.initialize(); }
    @Subscribe public EventTask shutdown(ProxyShutdownEvent event) { return application.shutdown(); }
    public boolean isReady() { return application.isReady(); }
}
