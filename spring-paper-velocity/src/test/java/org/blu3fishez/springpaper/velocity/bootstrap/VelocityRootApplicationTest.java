package org.blu3fishez.springpaper.velocity.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.blu3fishez.springpaper.velocity.fixture.application.GatewayApplication;
import org.blu3fishez.springpaper.velocity.fixture.application.GatewayService;
import org.blu3fishez.springpaper.velocity.fixture.input.GatewayListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

class VelocityRootApplicationTest {
    @TempDir Path directory;

    @Test void ordinaryRootScansTypedPropertiesAndReusesFactoryProductProxyBeforeBootReadiness() throws Exception {
        var host = new Host();
        Path artifact = configArtifact();
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("application.properties"),
                "gateway.greeting=external-base\nspring.config.import=extra.properties\n");
        Files.writeString(data.resolve("extra.properties"), "gateway.imported=relative-import\n");
        Files.writeString(data.resolve("application-fixture.properties"), "gateway.greeting=external-profile\n");
        var captured = new AtomicReference<ConfigurableApplicationContext>();
        var readyEvents = new ArrayList<String>();
        var app = new SpringVelocityApplication(host.plugin, host.proxy, host.logger, data,
                GatewayApplication.class, artifact, context -> {
                    captured.set(context);
                    context.addApplicationListener((ApplicationListener<ApplicationReadyEvent>) event -> {
                        assertEquals(1, host.listeners.size());
                        readyEvents.add("ready");
                    });
                    context.addApplicationListener((ApplicationListener<AvailabilityChangeEvent<?>>) event -> {
                        if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
                            assertEquals(1, host.listeners.size());
                            readyEvents.add("accepting");
                        }
                    });
                });
        try {
            execute(app.initialize());
            assertTrue(app.isReady());
            var service = captured.get().getBean(GatewayService.class);
            assertSame(host.proxy, service.proxy);
            assertEquals("external-profile", service.settings.greeting());
            assertEquals("relative-import", service.settings.imported());
            Object listener = captured.get().getBean("listener");
            assertSame(listener, host.listeners.getFirst());
            var nativeMethod = GatewayListener.class.getMethod("reload", ProxyReloadEvent.class);
            execute((EventTask) nativeMethod.invoke(listener, new ProxyReloadEvent()));
            assertEquals(1, service.advice.get());
            assertEquals(1, service.calls.get());
            assertEquals(java.util.List.of("ready", "accepting"), readyEvents);
            service.close = () -> assertTrue(host.listeners.isEmpty(), "native inputs close before consumer destruction");
            captured.get().close();
            assertFalse(app.isReady());
            assertEquals(1, service.closes.get());
            assertTrue(host.listeners.isEmpty());
        } finally { execute(app.shutdown()); }
    }

    @Test void readyListenerFailureCleansNativeInputsBeforeSpringDestroysConsumers() throws Exception {
        var host = new Host();
        var service = new AtomicReference<GatewayService>();
        var expected = new IllegalArgumentException("consumer ready failed");
        var app = new SpringVelocityApplication(host.plugin, host.proxy, host.logger, directory,
                GatewayApplication.class, configArtifact(), context -> context.addApplicationListener(
                        (ApplicationListener<ApplicationReadyEvent>) ignored -> {
                            GatewayService consumer = context.getBean(GatewayService.class);
                            service.set(consumer);
                            assertEquals(1, host.listeners.size());
                            consumer.close = () -> assertTrue(host.listeners.isEmpty());
                            throw expected;
                        }));
        assertSame(expected, assertThrows(IllegalArgumentException.class, () -> execute(app.initialize())));
        assertFalse(app.isReady());
        assertTrue(host.listeners.isEmpty());
        assertEquals(1, service.get().closes.get());
        execute(app.shutdown());
        assertEquals(1, service.get().closes.get());
    }

    private Path configArtifact() throws Exception {
        Path artifact = directory.resolve("application.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            jar.putNextEntry(new JarEntry("application.properties"));
            jar.write("gateway.greeting=packaged-base\nspring.profiles.active=fixture\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("application-fixture.properties"));
            jar.write("gateway.greeting=packaged-profile\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return artifact;
    }

    private static void execute(EventTask task) {
        assertTrue(task.requiresAsync());
        var resumed = new AtomicInteger();
        task.execute(new Continuation() {
            @Override public void resume() { resumed.incrementAndGet(); }
            @Override public void resumeWithException(Throwable error) { throw new AssertionError(error); }
        });
        assertEquals(1, resumed.get());
    }

    private static class Host {
        final Object plugin = new HostPlugin();
        final ProxyServer proxy = mock(ProxyServer.class);
        final Logger logger = mock(Logger.class);
        final EventManager events = mock(EventManager.class);
        final ArrayList<Object> listeners = new ArrayList<>();
        Host() {
            when(proxy.getEventManager()).thenReturn(events);
            doAnswer(call -> { listeners.add(call.getArgument(1)); return null; }).when(events).register(same(plugin), any());
            doAnswer(call -> { listeners.remove(call.getArgument(1)); return null; }).when(events).unregisterListener(same(plugin), any());
        }
    }
    private static class HostPlugin {}
}
