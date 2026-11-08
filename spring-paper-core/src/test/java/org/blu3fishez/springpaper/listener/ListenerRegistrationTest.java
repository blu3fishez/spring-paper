package org.blu3fishez.springpaper.listener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.blu3fishez.springpaper.service.PaperInvocationService;
import org.bukkit.Server;
import org.bukkit.event.*;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ListenerRegistrationTest {
    public static class ObservedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
    public static class Events implements Listener {
        int calls;
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onEvent(ObservedEvent event) { calls++; }
    }
    public static class OverriddenEvents extends Events {
        @Override @EventHandler
        public void onEvent(ObservedEvent event) { calls += 7; }
    }
    public static class DisabledOverride extends Events {
        @Override public void onEvent(ObservedEvent event) { calls += 11; }
    }
    public static class InheritedEvents extends Events {}

    @Test void registersAnAnnotatedOverrideOnce() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        OverriddenEvents bean = new OverriddenEvents();
        try (var registrar = new PaperListenerRegistrar(plugin, new PaperInvocationService(List.of()))) {
            registrar.register("overridden", bean, OverriddenEvents.class);
            var token = ArgumentCaptor.forClass(Listener.class);
            var callback = ArgumentCaptor.forClass(EventExecutor.class);
            verify(manager, times(1)).registerEvent(eq(ObservedEvent.class), token.capture(),
                    eq(EventPriority.NORMAL), callback.capture(), eq(plugin), eq(false));
            verifyNoMoreInteractions(manager);
            callback.getValue().execute(token.getValue(), new ObservedEvent());
            assertEquals(7, bean.calls);
        }
    }
    @Test void anUnannotatedOverrideDoesNotRetainTheParentHandler() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        try (var registrar = new PaperListenerRegistrar(plugin, new PaperInvocationService(List.of()))) {
            registrar.register("disabled", new DisabledOverride(), DisabledOverride.class);
            verifyNoInteractions(manager);
        }
    }
    @Test void keepsAnInheritedHandlerWhenItWasNotOverridden() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        InheritedEvents bean = new InheritedEvents();
        try (var registrar = new PaperListenerRegistrar(plugin, new PaperInvocationService(List.of()))) {
            registrar.register("inherited", bean, InheritedEvents.class);
            var token = ArgumentCaptor.forClass(Listener.class);
            var callback = ArgumentCaptor.forClass(EventExecutor.class);
            verify(manager).registerEvent(eq(ObservedEvent.class), token.capture(), eq(EventPriority.MONITOR),
                    callback.capture(), eq(plugin), eq(true));
            callback.getValue().execute(token.getValue(), new ObservedEvent());
            assertEquals(1, bean.calls);
        }
    }
    @Test void preservesNativeOptionsAndStopsStaleCallbacks() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        Events bean = new Events();
        var registrar = new PaperListenerRegistrar(plugin, new PaperInvocationService(List.of()));
        registrar.register("events", bean, Events.class);
        var token = ArgumentCaptor.forClass(Listener.class);
        var callback = ArgumentCaptor.forClass(EventExecutor.class);
        verify(manager).registerEvent(eq(ObservedEvent.class), token.capture(), eq(EventPriority.MONITOR), callback.capture(), eq(plugin), eq(true));
        callback.getValue().execute(token.getValue(), new ObservedEvent());
        assertEquals(1, bean.calls);
        registrar.close();
        callback.getValue().execute(token.getValue(), new ObservedEvent());
        assertEquals(1, bean.calls);
    }
}
