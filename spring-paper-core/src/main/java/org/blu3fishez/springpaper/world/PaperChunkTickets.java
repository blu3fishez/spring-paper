package org.blu3fishez.springpaper.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** Shared main-thread leases for the owning plugin; overlapping callers cannot remove each other's tickets. */
@RequiredArgsConstructor
public final class PaperChunkTickets implements AutoCloseable {
    public static final String BEAN_NAME = "paperChunkTickets";
    private final JavaPlugin plugin;
    private final Map<Key, Reference> references = new HashMap<>();
    private boolean closed;

    public Lease acquire(Chunk chunk) { return acquire(chunk.getWorld(), chunk.getX(), chunk.getZ()); }
    public Lease acquire(World world, int x, int z) {
        requireMain();
        if (closed) throw new IllegalStateException("Spring Paper chunk tickets are closed");
        Key key = new Key(world.getUID(), x, z);
        Reference existing = references.get(key);
        references.put(key, existing == null ? new Reference(1, world.addPluginChunkTicket(x, z, plugin))
                : new Reference(existing.count + 1, existing.owned));
        return new Lease(key);
    }

    private void release(Key key) {
        if (closed) return;
        requireMain();
        Reference reference = references.get(key);
        if (reference == null) return;
        if (reference.count > 1) references.put(key, new Reference(reference.count - 1, reference.owned));
        else { references.remove(key); removeOwned(key, reference); }
    }
    private void removeOwned(Key key, Reference reference) {
        World world = plugin.getServer().getWorld(key.worldId);
        if (world != null && reference.owned) world.removePluginChunkTicket(key.x, key.z, plugin);
    }
    private void requireMain() {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Chunk ticket leases require the Paper main thread");
    }
    @Override public void close() {
        if (closed) return;
        if (!references.isEmpty()) requireMain();
        closed = true;
        RuntimeException failure = null;
        for (var entry : references.entrySet()) {
            try { removeOwned(entry.getKey(), entry.getValue()); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        references.clear();
        if (failure != null) throw failure;
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public final class Lease implements AutoCloseable {
        private final Key key;
        private boolean released;
        @Override public void close() {
            if (released) return;
            release(key);
            released = true;
        }
    }
    private record Key(UUID worldId, int x, int z) {}
    private record Reference(int count, boolean owned) {}
}
