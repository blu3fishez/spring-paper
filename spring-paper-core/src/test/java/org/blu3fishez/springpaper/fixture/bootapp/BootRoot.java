package org.blu3fishez.springpaper.fixture.bootapp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aopalliance.intercept.MethodInterceptor;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.blu3fishez.springpaper.annotation.SpringPaperApplication;
import org.blu3fishez.springpaper.bootstrap.PaperApplicationReadyEvent;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

@SpringPaperApplication(name = "Ordinary", initializers = BootRoot.Capture.class)
public class BootRoot {
    public static volatile Probe lastProbe;
    public record Captured(JavaPlugin host, Thread thread, String message) {}
    public static class Capture implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getBeanFactory().registerSingleton("captured", new Captured(
                    (JavaPlugin) context.getBeanFactory().getSingleton("paperPlugin"), Thread.currentThread(),
                    context.getEnvironment().getProperty("game.message")));
        }
    }
    @Bean Probe probe(ConfigurableApplicationContext context, Captured captured,
            @Value("${fixture.failure:}") String failure) {
        return lastProbe = new Probe(context, captured, failure);
    }
    @Bean Resource resource(Probe probe) { return new Resource(probe); }
    @Bean Object refreshGuard(Resource resource, Probe probe) {
        if (probe.failure.equals("refresh")) throw new IllegalStateException("fixture refresh failure");
        return new Object();
    }
    @Bean Owner owner(Resource resource, Probe probe) { return new Owner(resource, probe); }
    @Bean ApplicationRunner runner(Probe probe) {
        return arguments -> {
            probe.worker();
            if (probe.events.contains("start")) throw new AssertionError("Native activation preceded Boot runner");
            probe.events.add("runner");
            if (probe.failure.equals("runner")) throw new IllegalStateException("fixture runner failure");
        };
    }
    @Bean @PaperEventListener GreetingProxyFactory listener(GreetingService service, Probe probe) {
        return new GreetingProxyFactory(service, probe);
    }
    public interface GreetingInput extends Listener { void accept(Signal signal); }
    public static class GreetingProxyFactory implements FactoryBean<GreetingInput> {
        private final GreetingInput proxy;
        GreetingProxyFactory(GreetingService service, Probe probe) {
            probe.worker();
            var factory = new ProxyFactory();
            factory.setTarget(new Input(service, probe));
            factory.setInterfaces(GreetingInput.class);
            factory.addAdvice((MethodInterceptor) invocation -> { probe.events.add("advice"); return invocation.proceed(); });
            proxy = (GreetingInput) factory.getProxy(Thread.currentThread().getContextClassLoader());
        }
        @Override public GreetingInput getObject() { return proxy; }
        @Override public Class<?> getObjectType() { return GreetingInput.class; }
    }
    public static class Signal extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    public static class Input implements GreetingInput {
        private final GreetingService service;
        private final Probe probe;
        Input(GreetingService service, Probe probe) { this.service = service; this.probe = probe; }
        @EventHandler @Override public void accept(Signal signal) {
            probe.main();
            probe.events.add("input:" + service.message());
        }
    }
    public static class Probe {
        public final ConfigurableApplicationContext context;
        public final Captured captured;
        public final List<String> events = new CopyOnWriteArrayList<>();
        public final String failure;
        Probe(ConfigurableApplicationContext context, Captured captured, String failure) {
            this.context = context; this.captured = captured; this.failure = failure;
            worker();
        }
        void main() {
            if (Thread.currentThread() != captured.thread()) throw new AssertionError("Expected Paper main");
            if (Thread.currentThread().getContextClassLoader() != context.getClassLoader()) throw new AssertionError("Lost native loader");
        }
        void worker() {
            if (Thread.currentThread() == captured.thread()) throw new AssertionError("Expected Boot preparation worker");
            if (Thread.currentThread().getContextClassLoader() != context.getClassLoader()) throw new AssertionError("Lost native loader");
        }
        @EventListener public void bootReady(ApplicationReadyEvent event) {
            worker();
            if (!events.contains("start")) throw new AssertionError("Boot ready preceded native activation");
            events.add("boot-ready");
            if (failure.equals("ready")) throw new IllegalStateException("fixture Boot ready failure");
        }
        @EventListener public void paperReady(PaperApplicationReadyEvent event) { main(); events.add("paper-ready"); }
        @EventListener public void availability(AvailabilityChangeEvent<?> event) {
            if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
                if (!events.contains("start")) throw new AssertionError("Accepted before native activation");
                events.add("accepting");
            }
            if (event.getState() == ReadinessState.REFUSING_TRAFFIC) events.add("refusing");
        }
    }
    public static class Resource implements AutoCloseable {
        private final Probe probe;
        boolean closed;
        Resource(Probe probe) { this.probe = probe; }
        @Override public void close() { closed = true; probe.events.add("destroy"); }
    }
    public static class Owner {
        private final Resource resource;
        private final Probe probe;
        Owner(Resource resource, Probe probe) { this.resource = resource; this.probe = probe; }
        @OnPaperMainThreadStartup public void start() {
            probe.main(); probe.events.add("start");
            if (probe.failure.equals("partial")) throw new IllegalStateException("fixture partial failure");
        }
        @OnPaperMainThreadShutdown public void stop() {
            probe.main();
            if (resource.closed) throw new AssertionError("Spring closed infrastructure before native cleanup");
            probe.events.add("stop");
        }
    }
}
