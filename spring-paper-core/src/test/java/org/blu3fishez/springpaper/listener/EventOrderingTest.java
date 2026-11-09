package org.blu3fishez.springpaper.listener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.blu3fishez.springpaper.annotation.PaperEventOrder;
import org.blu3fishez.springpaper.service.PaperInvocationService;
import org.bukkit.Server;
import org.bukkit.event.*;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class EventOrderingTest {
    public static class Damage extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    public static class Armor implements Listener {
        final List<String> calls;
        Armor(List<String> calls) { this.calls = calls; }
        @EventHandler(priority = EventPriority.HIGHEST)
        public void damage(Damage event) { calls.add("armor"); }
    }
    public static class Counter implements Listener {
        final List<String> calls;
        Counter(List<String> calls) { this.calls = calls; }
        @PaperEventOrder(after = Armor.class)
        @EventHandler(priority = EventPriority.HIGHEST)
        public void damage(Damage event) { calls.add("counter"); }
    }
    public static class ConfiguredCounter extends Counter {
        ConfiguredCounter(List<String> calls) { super(calls); }
        @Override @EventHandler(priority = EventPriority.HIGHEST)
        public void damage(Damage event) { calls.add("counter"); }
    }
    public static class ChildDamage extends Damage {}
    public static class ChildHandler implements Listener {
        final List<String> calls;
        ChildHandler(List<String> calls) { this.calls = calls; }
        @EventHandler public void damage(ChildDamage event) { calls.add("child"); }
    }
    public static class BaseAfterChild implements Listener {
        final List<String> calls;
        BaseAfterChild(List<String> calls) { this.calls = calls; }
        @PaperEventOrder(after = ChildHandler.class)
        @EventHandler public void damage(Damage event) { calls.add("base"); }
    }
    public static class CycleA implements Listener {
        @PaperEventOrder(after = CycleB.class)
        @EventHandler public void damage(Damage event) {}
    }
    public static class CycleB implements Listener {
        @PaperEventOrder(after = CycleA.class)
        @EventHandler public void damage(Damage event) {}
    }
    public static class NativeMonitor implements Listener {
        @EventHandler(priority = EventPriority.MONITOR) public void damage(Damage event) {}
    }
    public static class ConflictingLowest implements Listener {
        @PaperEventOrder(after = NativeMonitor.class)
        @EventHandler(priority = EventPriority.LOWEST) public void damage(Damage event) {}
    }
    public static class Other extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    public static class SeparateListA implements Listener {
        @PaperEventOrder(after = SeparateListB.class)
        @EventHandler public void damage(Damage event) {}
    }
    public static class SeparateListB implements Listener {
        @PaperEventOrder(after = SeparateListA.class)
        @EventHandler public void other(Other event) {}
    }

    private static class Host {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final PluginManager manager = mock(PluginManager.class);
        final List<EventExecutor> callbacks = new ArrayList<>();
        Host() {
            var server = mock(Server.class);
            when(plugin.getServer()).thenReturn(server);
            when(server.getPluginManager()).thenReturn(manager);
            doAnswer(call -> { callbacks.add(call.getArgument(3)); return null; }).when(manager)
                    .registerEvent(any(), any(), any(), any(), eq(plugin), anyBoolean());
        }
        PaperListenerRegistrar registrar() { return new PaperListenerRegistrar(plugin, new PaperInvocationService(List.of())); }
    }

    @Test void methodRelationshipOrdersOnlyTheRelevantEventWithoutNumericOrders() throws Exception {
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        List<String> calls = new ArrayList<>();
        List<EventExecutor> callbacks = new ArrayList<>();
        doAnswer(call -> { callbacks.add(call.getArgument(3)); return null; }).when(manager)
                .registerEvent(eq(Damage.class), any(Listener.class), eq(EventPriority.HIGHEST), any(), eq(plugin), eq(false));
        try (var registrar = new PaperListenerRegistrar(plugin, new PaperInvocationService(List.of()))) {
            registrar.registerAll(List.of(
                    new PaperListenerRegistrar.Candidate("firstByDefault", new Counter(calls), Counter.class),
                    new PaperListenerRegistrar.Candidate("lastByDefault", new Armor(calls), Armor.class)));
            for (var callback : callbacks) callback.execute(null, new Damage());
            assertEquals(List.of("armor", "counter"), calls);
        }
    }

    @Test void inheritedEventsSharingANativeHandlerListHonorTheSameRelationship() throws Exception {
        var host = new Host();
        List<String> calls = new ArrayList<>();
        try (var registrar = host.registrar()) {
            registrar.registerAll(List.of(
                    new PaperListenerRegistrar.Candidate("base", new BaseAfterChild(calls), BaseAfterChild.class),
                    new PaperListenerRegistrar.Candidate("child", new ChildHandler(calls), ChildHandler.class)));
            for (var callback : host.callbacks) callback.execute(null, new ChildDamage());
            assertEquals(List.of("child", "base"), calls);
        }
    }

    @Test void rejectsCyclesBeforeRegisteringAnyNativeInput() {
        var host = new Host();
        try (var registrar = host.registrar()) {
            var failure = assertThrows(IllegalArgumentException.class, () -> registrar.registerAll(List.of(
                    new PaperListenerRegistrar.Candidate("cycleA", new CycleA(), CycleA.class),
                    new PaperListenerRegistrar.Candidate("cycleB", new CycleB(), CycleB.class))));
            assertTrue(failure.getMessage().contains("cycleA#damage"));
            assertTrue(failure.getMessage().contains("cycleB#damage"));
            verifyNoInteractions(host.manager);
        }
    }

    @Test void cannotReversePaperNativePriorities() {
        var host = new Host();
        try (var registrar = host.registrar()) {
            assertThrows(IllegalArgumentException.class, () -> registrar.registerAll(List.of(
                    new PaperListenerRegistrar.Candidate("lowest", new ConflictingLowest(), ConflictingLowest.class),
                    new PaperListenerRegistrar.Candidate("monitor", new NativeMonitor(), NativeMonitor.class))));
            verifyNoInteractions(host.manager);
        }
    }

    @Test void unrelatedNativeHandlerListsDoNotCreateAnArtificialCycle() {
        var host = new Host();
        try (var registrar = host.registrar()) {
            registrar.registerAll(List.of(
                    new PaperListenerRegistrar.Candidate("a", new SeparateListA(), SeparateListA.class),
                    new PaperListenerRegistrar.Candidate("b", new SeparateListB(), SeparateListB.class)));
            assertEquals(2, host.callbacks.size());
        }
    }

    @Test void aRelationToAnInactiveRoleDoesNotRequireItsListener() throws Exception {
        var host = new Host();
        List<String> calls = new ArrayList<>();
        try (var registrar = host.registrar()) {
            registrar.registerAll(List.of(new PaperListenerRegistrar.Candidate("counter", new Counter(calls), Counter.class)));
            host.callbacks.getFirst().execute(null, new Damage());
            assertEquals(List.of("counter"), calls);
        }
    }

    @Test void compositionCanOrderSeparateFeaturesWithoutAnnotationsOnTheirImplementations() throws Exception {
        var host = new Host();
        List<String> calls = new ArrayList<>();
        try (var registrar = host.registrar()) {
            registrar.registerAll(List.of(
                    new PaperListenerRegistrar.Candidate("counter", new ConfiguredCounter(calls), ConfiguredCounter.class),
                    new PaperListenerRegistrar.Candidate("armor", new Armor(calls), Armor.class)),
                    List.of(new PaperEventRelation(Damage.class, EventPriority.HIGHEST, Armor.class, ConfiguredCounter.class)));
            for (var callback : host.callbacks) callback.execute(null, new Damage());
            assertEquals(List.of("armor", "counter"), calls);
        }
    }

    @Test void detectsACycleAcrossAnnotationAndCompositionBeforeNativeRegistration() {
        var host = new Host();
        try (var registrar = host.registrar()) {
            assertThrows(IllegalArgumentException.class, () -> registrar.registerAll(List.of(
                    new PaperListenerRegistrar.Candidate("armor", new Armor(new ArrayList<>()), Armor.class),
                    new PaperListenerRegistrar.Candidate("counter", new Counter(new ArrayList<>()), Counter.class)),
                    List.of(new PaperEventRelation(Damage.class, EventPriority.HIGHEST, Counter.class, Armor.class))));
            verifyNoInteractions(host.manager);
        }
    }
}
