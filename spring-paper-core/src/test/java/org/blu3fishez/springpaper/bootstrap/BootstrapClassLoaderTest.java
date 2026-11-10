package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

class BootstrapClassLoaderTest {
    @TempDir Path directory;
    @SpringPaperPluginApplication(name = "Isolated", configurations = Features.class)
    public static class Host extends JavaPlugin {}

    @Configuration(proxyBeanMethods = false)
    @Conditional(Enabled.class)
    public static class Features {
        @Bean String conditionallyCreated() { return "ready"; }
    }

    public static class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            assertSame(Host.class.getClassLoader(), context.getClassLoader());
            return true;
        }
    }

    @Test void constructsSpringInfrastructureWithThePluginLoaderAndRestoresTheHostLoader() throws Exception {
        Host plugin = mock(Host.class);
        Server server = mock(Server.class);
        when(server.getScheduler()).thenReturn(mock(org.bukkit.scheduler.BukkitScheduler.class));
        Thread paperThread = Thread.currentThread();
        when(server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == paperThread);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Isolated");
        when(plugin.namespace()).thenReturn("isolated");
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("class-loader-test"));
        when(plugin.getResource(anyString())).thenAnswer(call -> new ByteArrayInputStream(new byte[0]));
        ClassLoader original = paperThread.getContextClassLoader();
        // Like Paper's server loader, this loader cannot see the plugin's condition implementation.
        try (var hostLoader = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> hostLoader.loadClass(Enabled.class.getName()));
            paperThread.setContextClassLoader(hostLoader);
            try {
                try (var application = SpringPaperApplication.run(plugin)) {
                    assertEquals("ready", application.context().getBean("conditionallyCreated"));
                    assertSame(Host.class.getClassLoader(), application.context().getBeanFactory().getBeanClassLoader());
                    assertSame(hostLoader, paperThread.getContextClassLoader());
                }
                assertSame(hostLoader, paperThread.getContextClassLoader());
            } finally { paperThread.setContextClassLoader(original); }
        }
    }
}
