package org.blu3fishez.springpaper.security.api;

import io.papermc.paper.connection.PlayerConnection;
import java.net.SocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.blu3fishez.springpaper.security.connection.api.PaperSecuritySessionChangedEvent;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityConnectionInvalidatedEvent;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.bukkit.entity.Player;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.Ordered;

/**
 * Paper transport association and bounded invocation snapshot; the repository remains authoritative.
 * Player map uses object identity, so an old connection's quit never removes a replacement player.
 */
public final class PaperSecurityConnections implements ApplicationListener<PayloadApplicationEvent<?>>, Ordered {
    private final ConcurrentHashMap<UUID, Binding> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Transport, Binding> inbound = new ConcurrentHashMap<>();
    private final Map<Player, Binding> players = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Clock clock;
    private final Duration snapshotTtl;
    public PaperSecurityConnections(Clock clock, Duration snapshotTtl) {
        if (snapshotTtl.isNegative() || snapshotTtl.isZero()) throw new IllegalArgumentException("Snapshot TTL must be positive");
        this.clock = clock; this.snapshotTtl = snapshotTtl;
    }
    public PaperSecurityConnection begin(UUID playerId, String name, PlayerConnection transport) {
        var key = new Transport(playerId, java.util.Objects.requireNonNull(transport.getAddress(), "Transport address is required"));
        var binding = new Binding(PaperSecurityConnection.create(playerId, name), key, transport);
        if (inbound.putIfAbsent(key, binding) != null) throw new IllegalStateException("Transport already has a security session");
        byId.put(binding.connection.connectionId(), binding);
        return binding.connection;
    }
    /** Main thread only: bind the admitted transport to its actual Player instance. */
    public boolean attach(Player player) {
        var binding = inbound.get(new Transport(player.getUniqueId(), player.getConnection().getAddress()));
        if (binding == null || byId.get(binding.connection.connectionId()) != binding) return false;
        players.put(player, binding);
        binding.player = player;
        return true;
    }
    /** Nonblocking bounded snapshot. Validate core current(snapshot) before using it as invocation authentication. */
    public Optional<PaperSecuritySession> session(Player player) {
        Binding binding = players.get(player);
        if (binding == null || binding.invalidated || !fresh(binding)) return Optional.empty();
        return Optional.ofNullable(binding.snapshot.get());
    }
    public Optional<PaperSecurityConnection> connection(Player player) {
        Binding binding = players.get(player);
        return binding == null ? Optional.empty() : Optional.of(binding.connection);
    }
    public Optional<PaperSecurityConnection> connection(UUID playerId, SocketAddress address) {
        Binding binding = inbound.get(new Transport(playerId, address));
        return binding == null ? Optional.empty() : Optional.of(binding.connection);
    }
    public List<PaperSecurityConnection> connections() { return byId.values().stream().map(binding -> binding.connection).toList(); }
    public boolean current(PaperSecurityConnection connection) { return binding(connection) != null; }
    public boolean connected(PaperSecurityConnection connection) {
        Binding binding = binding(connection);
        return binding != null && binding.transport.isConnected();
    }
    public Optional<Player> player(PaperSecurityConnection connection) {
        Binding binding = binding(connection);
        return binding == null ? Optional.empty() : Optional.ofNullable(binding.player);
    }
    public boolean staleSnapshot(PaperSecurityConnection connection) {
        Binding binding = binding(connection);
        return binding != null && (binding.invalidated || (binding.snapshot.get() != null && !fresh(binding)));
    }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public boolean supportsAsyncExecution() { return false; }
    @Override public void onApplicationEvent(PayloadApplicationEvent<?> event) {
        // 비동기 multicaster를 쓰더라도 권한 snapshot의 차단/갱신은 소비자 관찰 이벤트보다 먼저 완료되어야 한다.
        if (event.getPayload() instanceof PaperSecuritySessionChangedEvent changed) changed(changed);
        if (event.getPayload() instanceof PaperSecurityConnectionInvalidatedEvent invalidated) invalidated(invalidated);
    }
    public void changed(PaperSecuritySessionChangedEvent event) { update(event.session()); }
    public void invalidated(PaperSecurityConnectionInvalidatedEvent event) {
        Binding binding = binding(event.connection());
        if (binding != null) binding.invalidated = true;
    }
    public void update(PaperSecuritySession session) {
        Binding binding = binding(session.connection());
        if (binding == null || binding.invalidated) return;
        synchronized (binding) {
            PaperSecuritySession previous = binding.snapshot.get();
            if (previous != null && previous.revision() > session.revision()) return;
            // 같은 revision의 재조회로 terminal 상태를 되돌리지 않는다.
            if (previous != null && previous.revision() == session.revision() && previous.status() != session.status()) return;
            binding.snapshot.set(session);
            binding.cachedAt = clock.instant();
        }
    }
    public void forget(PaperSecurityConnection connection) {
        Binding binding = binding(connection);
        if (binding == null || !byId.remove(connection.connectionId(), binding)) return;
        inbound.remove(binding.key, binding);
        if (binding.player != null) players.remove(binding.player, binding);
    }
    private boolean fresh(Binding binding) { return clock.instant().isBefore(binding.cachedAt.plus(snapshotTtl)); }
    private Binding binding(PaperSecurityConnection connection) {
        Binding binding = byId.get(connection.connectionId());
        return binding != null && binding.connection.equals(connection) ? binding : null;
    }
    private record Transport(UUID playerId, SocketAddress address) {}
    private static final class Binding {
        final PaperSecurityConnection connection;
        final Transport key;
        final PlayerConnection transport;
        final AtomicReference<PaperSecuritySession> snapshot = new AtomicReference<>();
        volatile Instant cachedAt = Instant.MIN;
        volatile Player player;
        volatile boolean invalidated;
        Binding(PaperSecurityConnection connection, Transport key, PlayerConnection transport) {
            this.connection = connection; this.key = key; this.transport = transport;
        }
    }
}
