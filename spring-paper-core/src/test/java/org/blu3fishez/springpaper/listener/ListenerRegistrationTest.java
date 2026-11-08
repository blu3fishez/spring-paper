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
