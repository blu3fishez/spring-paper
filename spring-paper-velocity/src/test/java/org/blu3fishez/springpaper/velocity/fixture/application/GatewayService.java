package org.blu3fishez.springpaper.velocity.fixture.application;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

@Service
public final class GatewayService implements DisposableBean {
    public final GatewaySettings settings;
    public final ProxyServer proxy;
    public final AtomicInteger advice = new AtomicInteger();
    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger closes = new AtomicInteger();
    public Runnable ready = () -> {};
    public Runnable close = () -> {};
    public GatewayService(GatewaySettings settings, ProxyServer proxy) { this.settings = settings; this.proxy = proxy; }
    @Override public void destroy() { close.run(); closes.incrementAndGet(); }
}
