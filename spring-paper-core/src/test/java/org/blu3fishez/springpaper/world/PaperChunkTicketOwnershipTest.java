package org.blu3fishez.springpaper.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

final class PaperChunkTicketOwnershipTest {
    @Test
    void overlappingLeasesKeepOneTicketUntilLastCloseAndCloseIsIdempotent() {
        var boundary = new Boundary();
        var world = boundary.world(false);
        var tickets = new PaperChunkTickets(boundary.plugin);
        var first = tickets.acquire(world, -1, 2);
        var second = tickets.acquire(world, -1, 2);
        assertEquals(Set.of(new Key(world.getUID(), -1, 2)), boundary.tickets);
        first.close();
        first.close();
        assertEquals(1, boundary.tickets.size());
        second.close();
        assertTrue(boundary.tickets.isEmpty());
        var third = tickets.acquire(world, -1, 2);
        assertEquals(1, boundary.tickets.size());
        third.close();
        assertTrue(boundary.tickets.isEmpty());
    }

    @Test
    void worldAndCoordinateAreBothPartOfLeaseIdentity() {
        var boundary = new Boundary();
        var firstWorld = boundary.world(false);
        var secondWorld = boundary.world(false);
        var tickets = new PaperChunkTickets(boundary.plugin);
        var first = tickets.acquire(firstWorld, 1, 2);
        var adjacent = tickets.acquire(firstWorld, 2, 2);
        var otherWorld = tickets.acquire(secondWorld, 1, 2);
        assertEquals(3, boundary.tickets.size());
        first.close();
        assertEquals(Set.of(new Key(firstWorld.getUID(), 2, 2), new Key(secondWorld.getUID(), 1, 2)), boundary.tickets);
        adjacent.close();
        otherWorld.close();
        assertTrue(boundary.tickets.isEmpty());
    }

    @Test
    void leaseDoesNotRemovePreexistingTicketOwnedByAnotherCaller() {
        var boundary = new Boundary();
        var world = boundary.world(true);
        var tickets = new PaperChunkTickets(boundary.plugin);
        var first = tickets.acquire(world, 1, 2);
        var second = tickets.acquire(world, 1, 2);
        first.close();
        second.close();
        assertEquals(Set.of(new Key(world.getUID(), 1, 2)), boundary.tickets);
    }

    @Test
    void unloadedWorldDoesNotPreventClearingLeaseReferences() {
        var boundary = new Boundary();
        var world = boundary.world(false);
        var tickets = new PaperChunkTickets(boundary.plugin);
        var lease = tickets.acquire(world, 1, 2);
        when(boundary.server.getWorld(world.getUID())).thenReturn(null);
        lease.close();
        boundary.tickets.clear();
        when(boundary.server.getWorld(world.getUID())).thenReturn(world);
        var replacement = tickets.acquire(world, 1, 2);
        assertEquals(1, boundary.tickets.size());
        replacement.close();
        assertTrue(boundary.tickets.isEmpty());
    }

    private record Key(UUID worldId, int x, int z) { }
    private static final class Boundary {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Server server = mock(Server.class);
        final Set<Key> tickets = new HashSet<>();
        Boundary() {
            when(plugin.getServer()).thenReturn(server);
            when(server.isPrimaryThread()).thenReturn(true);
        }
        World world(boolean preexisting) {
            var world = mock(World.class);
            var id = UUID.randomUUID();
            when(world.getUID()).thenReturn(id);
            when(server.getWorld(id)).thenReturn(world);
            when(world.addPluginChunkTicket(anyInt(), anyInt(), eq(plugin))).thenAnswer(call -> {
                var key = new Key(id, call.getArgument(0), call.getArgument(1));
                if (preexisting) tickets.add(key);
                return tickets.add(key);
            });
            when(world.removePluginChunkTicket(anyInt(), anyInt(), eq(plugin))).thenAnswer(call ->
                    tickets.remove(new Key(id, call.getArgument(0), call.getArgument(1))));
            return world;
        }
    }
}
