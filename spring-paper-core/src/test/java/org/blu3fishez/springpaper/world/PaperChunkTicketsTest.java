package org.blu3fishez.springpaper.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class PaperChunkTicketsTest {
    @Test void overlappingLeasesRemoveTheNativeTicketOnlyAfterTheLastRelease() {
        var host = new Host();
        try (var tickets = new PaperChunkTickets(host.plugin)) {
            var first = tickets.acquire(host.world, 4, 8);
            var second = tickets.acquire(host.world, 4, 8);
            first.close(); first.close();
            verify(host.world).addPluginChunkTicket(4, 8, host.plugin);
            verify(host.world, never()).removePluginChunkTicket(anyInt(), anyInt(), any());
            second.close();
            verify(host.world).removePluginChunkTicket(4, 8, host.plugin);
        }
    }
    @Test void aTicketOwnedOutsideTheLeasePoolSurvivesReleaseAndShutdown() {
        var host = new Host();
        when(host.world.addPluginChunkTicket(4, 8, host.plugin)).thenReturn(false);
        try (var tickets = new PaperChunkTickets(host.plugin)) { tickets.acquire(host.world, 4, 8).close(); }
        verify(host.world, never()).removePluginChunkTicket(anyInt(), anyInt(), any());
    }
    @Test void shutdownReleasesAllOwnedCoordinatesAndLateLeaseCloseIsHarmless() {
        var host = new Host();
        var tickets = new PaperChunkTickets(host.plugin);
        var first = tickets.acquire(host.world, 4, 8);
        tickets.acquire(host.world, 4, 8);
        tickets.acquire(host.world, 4, 9);
        tickets.close(); tickets.close(); first.close();
        verify(host.world).removePluginChunkTicket(4, 8, host.plugin);
        verify(host.world).removePluginChunkTicket(4, 9, host.plugin);
        assertThrows(IllegalStateException.class, () -> tickets.acquire(host.world, 4, 8));
    }
    @Test void offThreadReleaseDoesNotLoseTheLeaseOrItsNativeTicket() {
        var host = new Host();
        try (var tickets = new PaperChunkTickets(host.plugin)) {
            var lease = tickets.acquire(host.world, 4, 8);
            when(host.server.isPrimaryThread()).thenReturn(false);
            assertThrows(IllegalStateException.class, lease::close);
            verify(host.world, never()).removePluginChunkTicket(anyInt(), anyInt(), any());
            when(host.server.isPrimaryThread()).thenReturn(true);
            lease.close();
            verify(host.world).removePluginChunkTicket(4, 8, host.plugin);
        }
    }
    private static final class Host {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Server server = mock(Server.class);
        final World world = mock(World.class);
        Host() {
            when(plugin.getServer()).thenReturn(server);
            when(server.isPrimaryThread()).thenReturn(true);
            UUID id = UUID.randomUUID();
            when(world.getUID()).thenReturn(id);
            when(server.getWorld(id)).thenReturn(world);
            when(world.addPluginChunkTicket(anyInt(), anyInt(), eq(plugin))).thenReturn(true);
        }
    }
}
