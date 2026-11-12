package org.blu3fishez.springpaper.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.sql.DataSource;
import org.blu3fishez.springpaper.boot.BootApplication;
import org.blu3fishez.springpaper.boot.config.ConfigDataSettings;
import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.jdbc.fixture.boot.JdbcApplication;
import org.blu3fishez.springpaper.jdbc.fixture.boot.JdbcQueries;
import org.h2.jdbcx.JdbcDataSource;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class JdbcAutoConfigurationTest {
    @TempDir Path directory;

    @Test void dependencyPresenceAndExplicitFalseRequireNoDataSourceOrJdbcBeans() {
        for (Map<String, Object> properties : java.util.List.of(Map.<String, Object>of(),
                Map.<String, Object>of("spring.paper.jdbc.enabled", false))) {
            try (var context = context(properties, Root.class)) {
                assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
                assertTrue(context.getBeansOfType(JdbcOperations.class).isEmpty());
                assertTrue(context.getBeansOfType(JdbcClient.class).isEmpty());
            }
        }
    }

    @Test void ordinaryRootYamlEnablesImportedJdbcWithoutReplacingOrClosingConsumerResources() throws Exception {
        Path artifact = directory.resolve("application.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            jar.putNextEntry(new JarEntry("application.yml"));
            jar.write("spring:\n  paper:\n    jdbc:\n      enabled: true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:boot-jdbc-" + java.util.UUID.randomUUID());
        var source = new ConsumerSource(database);
        JdbcClient client = JdbcClient.create(source);
        ClassLoader loader = JdbcApplication.class.getClassLoader();
        var boot = new BootApplication(JdbcApplication.class, loader, new DefaultResourceLoader(loader),
                ConfigDataSettings.prepare(artifact, directory.resolve("data")), true,
                new BootApplication.Hooks(context -> {
                    PaperCoreConfiguration.registerHost(context, host(), new PaperConfiguration(context.getEnvironment()));
                    context.getBeanFactory().registerSingleton("consumerDataSource", source);
                    context.getBeanFactory().registerSingleton("consumerClient", client);
                }, ignored -> {}, ignored -> {}, () -> {}));
        try (var context = boot.run()) {
            assertEquals("true", context.getEnvironment().getProperty("spring.paper.jdbc.enabled"));
            assertTrue(context.containsBeanDefinition("paperJdbcTemplate"));
            assertSame(client, context.getBean(JdbcClient.class));
            assertSame(source, context.getBean(DataSource.class));
            assertEquals(1, context.getBeansOfType(DataSource.class).size());
            assertEquals(42, context.getBean(JdbcQueries.class).answer());
        }
        assertEquals(0, source.closes.get(), "external native/consumer singleton ownership stays external");
    }

    @Test void explicitImportStillEnablesJdbcWhenTheAutomaticFlagIsFalse() {
        try (var context = context(Map.of("spring.paper.jdbc.enabled", false), Explicit.class)) {
            assertTrue(context.containsBeanDefinition("paperJdbcTemplate"));
            assertFalse(context.containsBeanDefinition("org.blu3fishez.springpaper.jdbc.autoconfigure.JdbcAutoConfiguration"));
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?> root) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", properties));
        context.register(root);
        context.refresh();
        return context;
    }

    private JavaPlugin host() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        when(server.isPrimaryThread()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("JdbcFixture");
        when(plugin.namespace()).thenReturn("jdbcfixture");
        when(plugin.getDataFolder()).thenReturn(directory.resolve("data").toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        return plugin;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Root {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(JdbcConfiguration.class)
    static class Explicit {}

    private static final class ConsumerSource extends DelegatingDataSource implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();
        ConsumerSource(DataSource target) { super(target); }
        @Override public void close() { closes.incrementAndGet(); }
    }
}
