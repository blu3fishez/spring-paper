package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.*;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;

class ApplicationLifecycleTest {
    private static final List<String> actions = new ArrayList<>();
    private static Thread mainThread;
    private static boolean preparedOnWorker;

    @SpringPaperPluginApplication(name = "Test", configurations = Features.class)
    public static class Host extends JavaPlugin {}
    @Configuration(proxyBeanMethods = false)
    public static class Features {
        @Bean public Hooks hooks() { preparedOnWorker = Thread.currentThread() != mainThread; return new Hooks(); }
        @Bean public Resource resource() { return new Resource(); }
        @Bean public BoundSettings settings(@Value("${spring.paper.test-message:default-message}") String message) {
            return new BoundSettings(message);
        }
    }
    public record BoundSettings(String message) {}
    public static class Hooks implements AutoCloseable {
        @PaperStart public void start() { assertSame(mainThread, Thread.currentThread()); actions.add("start"); }
        @Override @PaperStop public void close() { assertSame(mainThread, Thread.currentThread()); actions.add("stop"); }
    }
    public static class Resource implements AutoCloseable {
        @Override public void close() { actions.add("destroy"); }
    }
    @SpringPaperPluginApplication(name = "Failing", configurations = FailingFeatures.class)
    public static class FailingHost extends JavaPlugin {}
    @Configuration(proxyBeanMethods = false)
    public static class FailingFeatures {
        @Bean public Broken broken() { return new Broken(); }
        @Bean public Resource resource() { return new Resource(); }
    }
    public static class Broken {
        @PaperStart public void start() { actions.add("partial"); throw new IllegalStateException("deliberate start failure"); }
        @PaperStop public void stop() { actions.add("rollback"); }
    }
    @SpringPaperPluginApplication(name = "Lazy", configurations = LazyFeatures.class)
    public static class LazyHost extends JavaPlugin {}
    @Configuration(proxyBeanMethods = false)
    public static class LazyFeatures {
        @Bean @Lazy public Hooks hooks() { return new Hooks(); }
    }

    private <T extends JavaPlugin> T host(Class<T> type) {
        mainThread = Thread.currentThread();
        T plugin = mock(type);
        Server server = mock(Server.class);
        when(server.isPrimaryThread()).thenAnswer(ignored -> Thread.currentThread() == mainThread);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Test");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data/absent"));
        when(plugin.getResource(anyString())).thenAnswer(ignored -> new ByteArrayInputStream(new byte[0]));
        return plugin;
    }
    @Test void preparesOnWorkerRunsHooksOnPaperThreadAndStopsExactlyOnce() {
        actions.clear();
        var application = SpringPaperApplication.run(host(Host.class));
        assertTrue(preparedOnWorker);
        assertEquals(List.of("start"), actions);
        application.close(); application.close();
        assertEquals(List.of("start", "stop", "destroy"), actions);
        assertFalse(application.context().isActive());
    }
    @Test void cleansPartialStartBeforeDestroyingInfrastructure() {
        actions.clear();
        assertThrows(IllegalStateException.class, () -> SpringPaperApplication.run(host(FailingHost.class)));
        assertEquals(List.of("partial", "rollback", "destroy"), actions);
    }
    @Test void rejectsLazyHooksWithoutSilentlySkippingTheirLifecycle() {
        actions.clear();
        assertThrows(IllegalStateException.class, () -> SpringPaperApplication.run(host(LazyHost.class)));
        assertEquals(List.of(), actions);
    }
    @Test void injectsYamlPropertiesThroughStandardSpringValue() {
        actions.clear();
        Host plugin = host(Host.class);
        when(plugin.getResource("application.yml")).thenAnswer(ignored -> new ByteArrayInputStream(
                "spring:\n  paper:\n    test-message: configured\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try (var application = SpringPaperApplication.run(plugin)) {
            assertEquals("configured", application.context().getBean(BoundSettings.class).message());
        }
    }
}
