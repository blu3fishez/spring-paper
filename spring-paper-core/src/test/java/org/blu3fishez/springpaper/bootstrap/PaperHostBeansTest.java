package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.config.PaperServerInfo;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PaperHostBeansTest {
    @Test void configurationExposesHostTypesBeforeAnyBeanIsInstantiated() {
        var beans = new DefaultListableBeanFactory();
        beans.setAllowBeanDefinitionOverriding(false);
        new AnnotatedBeanDefinitionReader(beans).register(PaperCoreConfiguration.class);
        new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(beans);
        String[] existingSingletons = beans.getSingletonNames();
        for (var expected : Map.of(JavaPlugin.class, "paperPlugin", Server.class, "paperServer",
                Logger.class, "paperLogger", PaperConfiguration.class, "paperConfiguration",
                PaperServerInfo.class, "paperServerInfo", PaperThreadAccess.class, "paperThreadAccess").entrySet()) {
            assertArrayEquals(new String[]{expected.getValue()}, beans.getBeanNamesForType(expected.getKey(), false, false));
            assertFalse(beans.containsSingleton(expected.getValue()));
        }
        assertArrayEquals(existingSingletons, beans.getSingletonNames());
    }

    @SpringPaperPluginApplication(name = "Host", configurations = Features.class, initializers = CaptureHost.class)
    public static class Host extends JavaPlugin implements InitializingBean, DisposableBean {
        @Override public void afterPropertiesSet() {}
        @Override public void destroy() {}
    }
    public record Captured(JavaPlugin plugin) {}
    public static class CaptureHost implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            assertFalse(context.isActive());
            var plugin = (JavaPlugin) context.getBeanFactory().getSingleton("paperPlugin");
            assertNotNull(plugin);
            context.getBeanFactory().registerSingleton("capturedHost", new Captured(plugin));
        }
    }
    public record Consumer(JavaPlugin plugin, Server server, Logger logger, PaperConfiguration configuration,
                           PaperServerInfo info, PaperThreadAccess threads) {}
    @Configuration(proxyBeanMethods = false)
    @Import(PaperCoreConfiguration.class)
    public static class Features {
        @Bean Consumer consumer(JavaPlugin plugin, Server server, Logger logger, PaperConfiguration configuration,
                PaperServerInfo info, PaperThreadAccess threads) {
            return new Consumer(plugin, server, logger, configuration, info, threads);
        }
        @Bean static BeanPostProcessor externalHostOwnership() {
            return new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof JavaPlugin) throw new AssertionError("Paper owns the plugin lifecycle");
                    return bean;
                }
            };
        }
    }

    @Test void initializerAndConsumersReceiveTheOnePaperOwnedInstanceWithTypedMetadata() {
        Host host = host();
        try (var application = SpringPaperApplication.run(host)) {
            var context = application.context();
            var consumer = context.getBean(Consumer.class);
            assertSame(host, consumer.plugin());
            assertSame(host, context.getBean(Captured.class).plugin());
            assertSame(host, context.getBean(Host.class));
            assertEquals(Map.of("paperPlugin", host), context.getBeansOfType(JavaPlugin.class));
            assertTrue(context.containsBeanDefinition("paperPlugin"));
            assertSame(host.getServer(), consumer.server());
            assertSame(host.getLogger(), consumer.logger());
            assertSame(context.getBean(PaperConfiguration.class), consumer.configuration());
            assertEquals("26.3", consumer.info().minecraftVersion());
            assertTrue(consumer.threads().isPrimaryThread());
            verify(host, never()).afterPropertiesSet();
        }
        verify(host, never()).destroy();
    }

    @Test void applicationsKeepHostBindingsSeparate() {
        Host first = host(), second = host();
        try (var a = SpringPaperApplication.run(first); var b = SpringPaperApplication.run(second)) {
            assertSame(first, a.context().getBean(JavaPlugin.class));
            assertSame(second, b.context().getBean(JavaPlugin.class));
            assertNotSame(a.context().getBean(Server.class), b.context().getBean(Server.class));
            assertNotSame(a.context().getBean(PaperConfiguration.class), b.context().getBean(PaperConfiguration.class));
        }
    }

    @Test void rejectsReplacementOfPaperOwnedBindings() {
        Host host = host();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setAllowBeanDefinitionOverriding(false);
            PaperCoreConfiguration.registerHost(context, host, new PaperConfiguration(context.getEnvironment()));
            context.registerBean("paperPlugin", JavaPlugin.class, this::host);
            assertThrows(RuntimeException.class, context::refresh);
        }
        verify(host, never()).afterPropertiesSet();
        verify(host, never()).destroy();
    }

    private Host host() {
        Thread main = Thread.currentThread();
        Host plugin = mock(Host.class);
        Server server = mock(Server.class);
        when(server.isPrimaryThread()).thenAnswer(ignored -> Thread.currentThread() == main);
        when(server.getMinecraftVersion()).thenReturn("26.3");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Host");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data/absent"));
        when(plugin.getResource(anyString())).thenAnswer(ignored -> new ByteArrayInputStream(new byte[0]));
        return plugin;
    }
}
