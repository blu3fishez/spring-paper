package org.blu3fishez.springpaper.velocity.fixture.input;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import org.blu3fishez.springpaper.velocity.annotation.VelocityEventListener;
import org.blu3fishez.springpaper.velocity.fixture.application.GatewayService;

@VelocityEventListener
public class GatewayListener {
    private final GatewayService service;
    public GatewayListener(GatewayService service) { this.service = service; }
    @Subscribe public EventTask reload(ProxyReloadEvent ignored) {
        return EventTask.async(service.calls::incrementAndGet);
    }
}
