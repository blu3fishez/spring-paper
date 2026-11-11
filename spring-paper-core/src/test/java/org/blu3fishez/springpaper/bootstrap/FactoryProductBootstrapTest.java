package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class FactoryProductBootstrapTest {
    private static Thread paperThread;
    public static class Signal extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    public interface InputOperations extends Listener, org.bukkit.command.CommandExecutor {
        void start();
        void stop();
        void accept(Signal signal);
    }
    @PaperEventListener
    @PaperCommandExecutor(commands = "factory")
    public static class Input implements InputOperations {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        @OnPaperMainThreadStartup public void start() { starts.incrementAndGet(); }
        @OnPaperMainThreadShutdown public void stop() { stops.incrementAndGet(); }
        final AtomicInteger events = new AtomicInteger();
        final AtomicInteger commands = new AtomicInteger();
        @EventHandler public void accept(Signal signal) { events.incrementAndGet(); }
        @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                String label, String[] args) { commands.incrementAndGet(); return true; }
    }
    public static class InputFactory implements FactoryBean<Input>, AutoCloseable {
        final Input input = new Input();
        final AtomicInteger factoryStarts = new AtomicInteger();
        final AtomicInteger factoryCloses = new AtomicInteger();
        @OnPaperMainThreadStartup public void startFactory() { factoryStarts.incrementAndGet(); }
        @Override public void close() { factoryCloses.incrementAndGet(); }
        @Override public Input getObject() {
            assertNotSame(paperThread, Thread.currentThread(), "Product construction belongs to preparation worker");
            return input;
        }
        @Override public Class<?> getObjectType() { return Input.class; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Features {
        @Bean InputFactory input() { return new InputFactory(); }
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor productPreparationThread() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof Input) assertNotSame(paperThread, Thread.currentThread());
                    return bean;
                }
            };
        }
    }
    @SpringPaperPluginApplication(name = "FactoryProducts", configurations = Features.class)
    public static class Host extends JavaPlugin {}

    @Test void registersAndRunsHooksOnSingletonFactoryProduct() {
        Host host = host(Host.class);
        Input product;
        InputFactory factory;
        try (var application = SpringPaperApplication.run(host)) {
            product = application.context().getBean(Input.class);
            factory = application.context().getBean("&input", InputFactory.class);
            assertEquals(0, factory.factoryStarts.get());
            assertEquals(1, product.starts.get(), "Factory product startup must run before input registration");
            verify(host.getServer().getPluginManager()).registerEvent(eq(Signal.class), any(), any(), any(), eq(host), eq(false));
        }
        assertEquals(1, product.stops.get());
        assertEquals(1, factory.factoryCloses.get());
    }

    public static class PrototypeFactory extends InputFactory {
        @Override public Input getObject() { return new Input(); }
        @Override public boolean isSingleton() { return false; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class PrototypeFeatures {
        @Bean PrototypeFactory input() { return new PrototypeFactory(); }
    }
    @SpringPaperPluginApplication(name = "Prototype", configurations = PrototypeFeatures.class)
    public static class PrototypeHost extends JavaPlugin {}
    @Test void rejectsPrototypeFactoryProductsBeforeAnyNativeRegistration() {
        var host = host(PrototypeHost.class);
        var failure = assertThrows(IllegalStateException.class, () -> SpringPaperApplication.run(host));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertTrue(cause.getMessage().contains("FactoryBean products must also be singletons"));
        verifyNoInteractions(host.getServer().getPluginManager());
    }

    public static class BrokenFactory extends InputFactory {
        @Override public Input getObject() { throw new IllegalStateException("product preparation failed"); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class BrokenFeatures {
        static BrokenFactory owner;
        @Bean BrokenFactory input() { return owner = new BrokenFactory(); }
    }
    @SpringPaperPluginApplication(name = "BrokenFactory", configurations = BrokenFeatures.class)
    public static class BrokenHost extends JavaPlugin {}
    @Test void failedProductPreparationClosesItsFactoryWithoutStartingPaperHooks() {
        var host = host(BrokenHost.class);
        assertThrows(IllegalStateException.class, () -> SpringPaperApplication.run(host));
        assertEquals(1, BrokenFeatures.owner.factoryCloses.get());
        assertEquals(0, BrokenFeatures.owner.input.starts.get());
        assertEquals(0, BrokenFeatures.owner.input.stops.get());
        verifyNoInteractions(host.getServer().getPluginManager());
    }

    public static class PlainInput implements Listener {
        @EventHandler public void accept(Signal signal) {}
    }
    @SuppressWarnings("rawtypes")
    public static class RawFactory implements FactoryBean {
        final PlainInput input = new PlainInput();
        @Override public Object getObject() { return input; }
        @Override public Class<?> getObjectType() { return null; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class FactoryMethodFeatures {
        @Bean @PaperEventListener RawFactory input() { return new RawFactory(); }
    }
    @SpringPaperPluginApplication(name = "RawFactory", configurations = FactoryMethodFeatures.class)
    public static class RawHost extends JavaPlugin {}
    @Test void factoryMethodIntentFindsRawFactoryProductWithoutDependingOnProductTypePrediction() {
        var host = host(RawHost.class);
        try (var application = SpringPaperApplication.run(host)) {
            verify(host.getServer().getPluginManager()).registerEvent(eq(Signal.class), any(), any(), any(), eq(host), eq(false));
            assertSame(application.context().getBean("input"), application.context().getBean("input"));
        }
    }

    public static class ProxyInputFactory implements FactoryBean<InputOperations> {
        final Input target = new Input();
        final AtomicInteger advice = new AtomicInteger();
        final InputOperations proxy;
        ProxyInputFactory(boolean classProxy) {
            var factory = new org.springframework.aop.framework.ProxyFactory(target);
            factory.setProxyTargetClass(classProxy);
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                advice.incrementAndGet(); return invocation.proceed();
            });
            proxy = (InputOperations) factory.getProxy();
        }
        @Override public InputOperations getObject() { return proxy; }
        @Override public Class<?> getObjectType() { return Input.class; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class ClassProxyFeatures {
        @Bean ProxyInputFactory input() { return new ProxyInputFactory(true); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class JdkProxyFeatures {
        @Bean ProxyInputFactory input() { return new ProxyInputFactory(false); }
    }
    @SpringPaperPluginApplication(name = "ClassProxy", configurations = ClassProxyFeatures.class)
    public static class ClassProxyHost extends JavaPlugin {}
    @SpringPaperPluginApplication(name = "JdkProxy", configurations = JdkProxyFeatures.class)
    public static class JdkProxyHost extends JavaPlugin {}
    @Test void factoryProductsKeepClassAndJdkProxyAdviceForInputAndLifecycle() throws Exception {
        for (var type : java.util.List.of(ClassProxyHost.class, JdkProxyHost.class)) {
            var host = host(type);
            ProxyInputFactory owner;
            try (var application = SpringPaperApplication.run(host)) {
                owner = application.context().getBean("&input", ProxyInputFactory.class);
                var callback = org.mockito.ArgumentCaptor.forClass(org.bukkit.plugin.EventExecutor.class);
                var token = org.mockito.ArgumentCaptor.forClass(Listener.class);
                verify(host.getServer().getPluginManager()).registerEvent(eq(Signal.class), token.capture(), any(), callback.capture(), eq(host), eq(false));
                callback.getValue().execute(token.getValue(), new Signal());
                var command = org.mockito.ArgumentCaptor.forClass(org.bukkit.command.CommandExecutor.class);
                verify(host.getCommand("factory")).setExecutor(command.capture());
                command.getValue().onCommand(mock(org.bukkit.command.CommandSender.class), host.getCommand("factory"), "factory", new String[0]);
                assertEquals(1, owner.target.starts.get());
                assertEquals(1, owner.target.events.get());
                assertEquals(1, owner.target.commands.get());
            }
            assertEquals(1, owner.target.stops.get());
            assertEquals(4, owner.advice.get());
        }
    }

    public static class ExistingInitializer implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            context.getBeanFactory().registerSingleton("input", new Input());
        }
    }
    @Configuration(proxyBeanMethods = false)
    public static class NoFeatures {}
    @SpringPaperPluginApplication(name = "Existing", configurations = NoFeatures.class, initializers = ExistingInitializer.class)
    public static class ExistingHost extends JavaPlugin {}
    @Test void existingSingletonIsReusedForBothHooksAndInput() {
        var host = host(ExistingHost.class);
        Input input;
        try (var application = SpringPaperApplication.run(host)) {
            input = application.context().getBean(Input.class);
            assertEquals(1, input.starts.get());
            verify(host.getServer().getPluginManager()).registerEvent(eq(Signal.class), any(), any(), any(), eq(host), eq(false));
        }
        assertEquals(1, input.stops.get());
    }

    private <T extends JavaPlugin> T host(Class<T> type) {
        Thread paper = Thread.currentThread();
        paperThread = paper;
        T host = mock(type);
        Server server = mock(Server.class);
        when(server.getScheduler()).thenReturn(mock(org.bukkit.scheduler.BukkitScheduler.class));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == paper);
        when(host.getServer()).thenReturn(server);
        when(host.getName()).thenReturn("FactoryProducts");
        var command = mock(org.bukkit.command.PluginCommand.class);
        when(command.getName()).thenReturn("factory");
        when(host.getCommand("factory")).thenReturn(command);
        when(host.namespace()).thenReturn("factoryproducts");
        when(host.getLogger()).thenReturn(Logger.getLogger("factory-products-test"));
        when(host.getDataFolder()).thenReturn(new java.io.File("build/test-data/absent-factory-products"));
        when(host.getResource(anyString())).thenAnswer(call -> new ByteArrayInputStream(new byte[0]));
        return host;
    }
}
