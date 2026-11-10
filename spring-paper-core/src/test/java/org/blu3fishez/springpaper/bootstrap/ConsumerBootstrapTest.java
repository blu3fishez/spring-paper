package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.*;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

class ConsumerBootstrapTest {
    private static final List<String> actions = new ArrayList<>();
    private static Thread paperThread;
    public record ExistingSettings(String role) {}
    public record Prepared(ExistingSettings settings) {}
    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            assertSame(paperThread, Thread.currentThread());
            assertFalse(context.isActive());
            context.getBeanFactory().registerSingleton("existingSettings", new ExistingSettings("DUNGEON"));
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "consumerPhases", java.util.Map.of("consumer.guide.start", 30, "consumer.guide.stop", -10)));
        }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Features {
        @Bean Prepared prepared(ExistingSettings settings) {
            assertNotSame(paperThread, Thread.currentThread());
            return new Prepared(settings);
        }
        @Bean GuideFlush flush() { return new GuideFlush(); }
        @Bean Producer producer() { return new Producer(); }
    }
    public static class GuideFlush {
        @PaperStart(phaseString = "${consumer.guide.start}") public void start() { actions.add("guide-start"); }
        @PaperStop(phaseString = "${consumer.guide.stop}") public void stop() { actions.add("guide-flush"); }
    }
    static class Producer {
        @PaperStart(phase = 10) public void start() { actions.add("producer-start"); }
        @PaperStop(phase = 10) public void stop() { actions.add("producer-stop"); }
    }
    @SpringPaperPluginApplication(name = "Consumer", configurations = Features.class, initializers = Initializer.class)
    public static class Host extends JavaPlugin {}

    @Test void capturesHostSettingsBeforeWorkerRefreshAndSupportsIndependentStopPhases() {
        actions.clear();
        Host host = host(Host.class);
        try (var application = SpringPaperApplication.run(host)) {
            assertSame(application.context().getBean(ExistingSettings.class), application.context().getBean(Prepared.class).settings());
            assertEquals(List.of("producer-start", "guide-start"), actions);
        }
        assertEquals(List.of("producer-start", "guide-start", "producer-stop", "guide-flush"), actions);
    }

    @Configuration(proxyBeanMethods = false)
    public static class BrokenFeatures {
        @Bean Broken broken() { return new Broken(); }
        @Bean NeverStarted neverStarted() { return new NeverStarted(); }
        @Bean Resource resource() { return new Resource(); }
    }
    public static class NeverStarted {
        @PaperStart(phase = 30) public void start() { actions.add("must-not-start"); }
        @PaperStop(phase = 200) public void stop() { actions.add("must-not-stop"); }
    }
    public static class Broken {
        @PaperStart(phase = 20) public void start() { actions.add("partial-start"); throw new IllegalStateException("broken"); }
        @PaperStop(phase = 100) public void stop() { actions.add("partial-stop"); }
    }
    public static class Resource implements AutoCloseable {
        @Override public void close() { actions.add("resource-close"); }
    }
    @SpringPaperPluginApplication(name = "Policy", configurations = BrokenFeatures.class)
    public static class PolicyHost extends SpringPaperPlugin {
        @Override protected void onApplicationStartupFailure(Throwable failure) {
            actions.add("host-failure-policy");
            getServer().shutdown();
        }
    }
    @Test void invokesConsumerFailurePolicyAfterPartialCleanupWithoutStartingLaterHooks() {
        actions.clear();
        PolicyHost host = host(PolicyHost.class);
        doCallRealMethod().when(host).onEnable();
        doCallRealMethod().when(host).onApplicationStartupFailure(any());
        assertThrows(IllegalStateException.class, host::onEnable);
        assertEquals(List.of("partial-start", "partial-stop", "resource-close", "host-failure-policy"), actions);
        verify(host.getServer()).shutdown();
    }

    public static class Signal extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    @PaperEventListener @Order(20)
    public static class Later implements Listener {
        @EventHandler public void accept(Signal event) { actions.add("later-handler"); }
    }
    @PaperEventListener @Order(200)
    static class Earlier implements Listener, org.springframework.core.Ordered {
        @Override public int getOrder() { return 10; }
        @EventHandler public void accept(Signal event) { actions.add("earlier-handler"); }
    }
    @PaperEventListener
    static class Priority implements Listener, org.springframework.core.PriorityOrdered {
        @Override public int getOrder() { return Integer.MAX_VALUE; }
        @EventHandler public void accept(Signal event) { actions.add("priority-handler"); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Events {
        @Bean Later aLater() { return new Later(); }
        @Bean Earlier zEarlier() { return new Earlier(); }
        @Bean Priority priority() { return new Priority(); }
    }
    @SpringPaperPluginApplication(name = "Ordered", configurations = Events.class)
    public static class OrderedHost extends JavaPlugin {}
    @Test void honorsNativeOrderForEqualPriorityListenersInsteadOfBeanName() throws Exception {
        actions.clear();
        OrderedHost host = host(OrderedHost.class);
        PluginManager manager = mock(PluginManager.class);
        when(host.getServer().getPluginManager()).thenReturn(manager);
        try (var application = SpringPaperApplication.run(host)) {
            var callbacks = ArgumentCaptor.forClass(EventExecutor.class);
            var tokens = ArgumentCaptor.forClass(Listener.class);
            verify(manager, times(3)).registerEvent(eq(Signal.class), tokens.capture(), any(), callbacks.capture(), eq(host), eq(false));
            for (int i = 0; i < 3; i++) callbacks.getAllValues().get(i).execute(tokens.getAllValues().get(i), new Signal());
            assertEquals(List.of("priority-handler", "earlier-handler", "later-handler"), actions);
        }
    }
    static class Owner {
        final CapturedListener listener = new CapturedListener(this);
    }
    static class CapturedListener implements Listener {
        private final Owner owner;
        CapturedListener(Owner owner) { this.owner = owner; }
        @EventHandler public void accept(Signal event) { actions.add("captured-handler"); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class FactoryEvents {
        @Bean Owner owner() { return new Owner(); }
        @Bean @PaperEventListener @Order(10) CapturedListener captured(Owner owner) { return owner.listener; }
        @Bean Later later() { return new Later(); }
    }
    @SpringPaperPluginApplication(name = "FactoryEvents", configurations = FactoryEvents.class)
    public static class FactoryHost extends JavaPlugin {}
    @Test void registersFactoryMethodListenerWithoutRecreatingItsCapturedOwner() throws Exception {
        actions.clear();
        FactoryHost host = host(FactoryHost.class);
        PluginManager manager = mock(PluginManager.class);
        when(host.getServer().getPluginManager()).thenReturn(manager);
        try (var application = SpringPaperApplication.run(host)) {
            Owner owner = application.context().getBean(Owner.class);
            CapturedListener listener = application.context().getBean(CapturedListener.class);
            assertSame(owner.listener, listener);
            assertSame(owner, listener.owner);
            var callback = ArgumentCaptor.forClass(EventExecutor.class);
            var token = ArgumentCaptor.forClass(Listener.class);
            verify(manager, times(2)).registerEvent(eq(Signal.class), token.capture(), any(), callback.capture(), eq(host), eq(false));
            for (int i = 0; i < 2; i++) callback.getAllValues().get(i).execute(token.getAllValues().get(i), new Signal());
            assertEquals(List.of("captured-handler", "later-handler"), actions);
        }
    }
    @Configuration(proxyBeanMethods = false)
    public static class LazyFactoryEvents {
        @Bean @PaperEventListener @org.springframework.context.annotation.Lazy
        CapturedListener listener() { throw new AssertionError("Must reject the definition before creating it"); }
    }
    @SpringPaperPluginApplication(name = "LazyFactoryEvents", configurations = LazyFactoryEvents.class)
    public static class LazyFactoryHost extends JavaPlugin {}
    @Test void rejectsLazyFactoryListenerInsteadOfSilentlyMissingItsRegistration() {
        var error = assertThrows(IllegalStateException.class, () -> SpringPaperApplication.run(host(LazyFactoryHost.class)));
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("must be eager singletons"));
    }
    @PaperCommandExecutor(commands = "reset", usage = "/reset <player>")
    @PaperCommandExecutor(commands = "reset-all", usage = "/reset-all confirm")
    public static class SharedCommand implements org.bukkit.command.CommandExecutor {
        @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            actions.add(label); return true;
        }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Commands { @Bean SharedCommand sharedCommand() { return new SharedCommand(); } }
    @SpringPaperPluginApplication(name = "Commands", configurations = Commands.class)
    public static class CommandHost extends JavaPlugin {}
    @Test void connectsEveryRepeatedCommandToOneManagedExecutor() {
        actions.clear();
        CommandHost host = host(CommandHost.class);
        var first = mock(org.bukkit.command.PluginCommand.class);
        var second = mock(org.bukkit.command.PluginCommand.class);
        // Registrar는 alias를 canonical 이름으로 오인하지 않게 검사하므로 mock에도 실제 명령 이름을 제공한다.
        when(first.getName()).thenReturn("reset");
        when(second.getName()).thenReturn("reset-all");
        when(host.getCommand("reset")).thenReturn(first);
        when(host.getCommand("reset-all")).thenReturn(second);
        try (var application = SpringPaperApplication.run(host)) {
            var callback = ArgumentCaptor.forClass(org.bukkit.command.CommandExecutor.class);
            verify(first).setExecutor(callback.capture());
            verify(second).setExecutor(callback.capture());
            callback.getAllValues().get(0).onCommand(mock(org.bukkit.command.CommandSender.class), first, "reset", new String[0]);
            callback.getAllValues().get(1).onCommand(mock(org.bukkit.command.CommandSender.class), second, "reset-all", new String[0]);
            assertEquals(List.of("reset", "reset-all"), actions);
        }
    }
    private <T extends JavaPlugin> T host(Class<T> type) {
        paperThread = Thread.currentThread();
        T host = mock(type);
        Server server = mock(Server.class);
        when(server.getScheduler()).thenReturn(mock(org.bukkit.scheduler.BukkitScheduler.class));
        when(server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == paperThread);
        when(host.getServer()).thenReturn(server);
        when(host.getName()).thenReturn("Consumer");
        when(host.namespace()).thenReturn("consumer");
        when(host.getLogger()).thenReturn(Logger.getLogger("consumer-test"));
        when(host.getDataFolder()).thenReturn(new java.io.File("build/test-data/absent-consumer"));
        when(host.getResource(anyString())).thenAnswer(call -> new ByteArrayInputStream(new byte[0]));
        return host;
    }
}
