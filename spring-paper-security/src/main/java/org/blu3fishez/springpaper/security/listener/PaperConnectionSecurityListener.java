package org.blu3fishez.springpaper.security.listener;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.blu3fishez.springpaper.annotation.PaperStart;
import org.blu3fishez.springpaper.annotation.PaperStop;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.security.api.PaperSecurityConnections;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityRequest;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.spi.PaperConnectionAdmissionHandler;
import org.blu3fishez.springpaper.security.spi.PaperConnectionAuthenticationConverter;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Opt-in Paper adapter. Consumers may disable it and call the core API from their own Paper/Velocity adapter. */
@PaperEventListener
public final class PaperConnectionSecurityListener implements org.bukkit.event.Listener {
    private final JavaPlugin plugin;
    private final PaperTasks tasks;
    private final PaperConnectionSecurityService security;
    private final PaperSecurityConnections connections;
    private final PaperConnectionAuthenticationConverter converter;
    private final PaperConnectionAdmissionHandler admission;
    private final int timeoutSeconds;
    private final long refreshTicks;
    private final String deniedMessage;
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private BukkitTask polling;
    public PaperConnectionSecurityListener(JavaPlugin plugin, PaperTasks tasks, PaperConnectionSecurityService security,
            PaperSecurityConnections connections, PaperConnectionAuthenticationConverter converter,
            PaperConnectionAdmissionHandler admission, PaperConfiguration configuration) {
        this.plugin = plugin; this.tasks = tasks; this.security = security; this.connections = connections;
        this.converter = converter; this.admission = admission;
        timeoutSeconds = configuration.integer("spring.paper.security.connection.admission-timeout-seconds", 10);
        refreshTicks = configuration.integer("spring.paper.security.connection.refresh-period-ticks", 20);
        deniedMessage = configuration.text("spring.paper.security.denied-message", "Authentication is required or unavailable.");
        if (timeoutSeconds <= 0 || refreshTicks <= 0) throw new IllegalArgumentException("Connection security timeouts/periods must be positive");
    }
    @PaperStart(phase = -100) public void start() {
        // 활성화 당시 이미 접속한 플레이어에게는 이 컨텍스트의 인증 증거가 없다. 기본 어댑터는 재접속을 요구한다.
        // 소비자의 기존 세션 복원 방식이 있으면 이 어댑터를 끄고 core API/자체 resolver를 연결한다.
        plugin.getServer().getOnlinePlayers().forEach(player -> admission.revoke(player, "security_context_started"));
        polling = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, refreshTicks, refreshTicks);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (closed || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        PaperSecurityConnection connection = null;
        try {
            if (event.getConnection() == null) throw new IllegalArgumentException("Paper 26.3 login connection is required");
            connection = connections.begin(event.getUniqueId(), event.getName(), event.getConnection());
            var result = security.open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, converter.convert(event),
                    java.util.Map.of("remote-address", event.getAddress().getHostAddress())))
                    .toCompletableFuture().get(timeoutSeconds, TimeUnit.SECONDS);
            if (closed || !connections.current(connection) || !event.getConnection().isConnected()) {
                throw new IllegalStateException("Login connection is no longer current");
            }
            if ((result.status() == PaperSecuritySession.Status.AUTHENTICATED || result.status() == PaperSecuritySession.Status.PENDING)
                    && !security.current(result)) throw new IllegalStateException("Authentication was revoked before admission");
            admission.admit(event, result);
            // 소비자는 pending 입장 방식을 바꿀 수 있지만 terminal 거절을 인증 완료로 바꿀 수는 없다.
            if (result.status() != PaperSecuritySession.Status.AUTHENTICATED && result.status() != PaperSecuritySession.Status.PENDING
                    && event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) deny(event);
            if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) release(connection);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (connection != null) release(connection);
            deny(event);
            plugin.getLogger().warning("Connection security rejected a login (" + failure.getClass().getSimpleName() + ")");
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void joined(PlayerJoinEvent event) {
        if (closed) return;
        var player = event.getPlayer();
        if (!connections.attach(player)) { admission.revoke(player, "missing_security_session"); return; }
        var snapshot = connections.session(player);
        if (snapshot.isEmpty() || !security.current(snapshot.get())) {
            admission.revoke(player, "revoked_before_join");
            connections.connection(player).ifPresent(this::release);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { connections.connection(event.getPlayer()).ifPresent(this::release); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void connectionClosed(PlayerConnectionCloseEvent event) {
        connections.connection(event.getPlayerUniqueId(), event.getSocketAddress()).ifPresent(connection -> {
            // UUID/IP만으로 제거하면 이전 연결의 지연 close가 새 접속을 지울 수 있다. 실제 transport까지 확인한다.
            if (!connections.connected(connection)) release(connection);
        });
    }
    private void refresh() {
        if (closed) return;
        for (var connection : connections.connections()) {
            if (!connections.connected(connection)) { release(connection); continue; }
            if (connections.staleSnapshot(connection)) { revoke(connection, "stale_security_snapshot"); continue; }
            if (!refreshing.add(connection.connectionId())) continue;
            security.session(connection).whenComplete((session, failure) -> {
                refreshing.remove(connection.connectionId());
                if (closed || !connections.current(connection)) return;
                if (failure != null || session.isEmpty()) {
                    // 권한 차단을 다음 Paper tick의 kick까지 미루지 않는다. 네트워크 효과만 메인 스레드로 넘긴다.
                    security.disconnect(connection).exceptionally(cleanup -> null);
                    tasks.main(() -> revoke(connection, "security_session_unavailable"));
                } else {
                    connections.update(session.get());
                    var status = session.get().status();
                    if (status != PaperSecuritySession.Status.AUTHENTICATED && status != PaperSecuritySession.Status.PENDING) {
                        tasks.main(() -> revoke(connection, session.get().reason()));
                    }
                }
            });
        }
    }
    private void revoke(PaperSecurityConnection connection, String reason) {
        if (!connections.current(connection)) return;
        connections.player(connection).ifPresent(player -> {
            if (player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player) admission.revoke(player, reason);
        });
        release(connection);
    }
    private void release(PaperSecurityConnection connection) {
        connections.forget(connection);
        security.disconnect(connection).exceptionally(failure -> {
            plugin.getLogger().warning("Connection security cleanup failed (" + failure.getClass().getSimpleName() + ")");
            return null;
        });
    }
    private void deny(AsyncPlayerPreLoginEvent event) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(deniedMessage));
    }
    @PaperStop public void stop() {
        closed = true;
        if (polling != null) polling.cancel();
        connections.connections().forEach(connections::forget);
        security.close();
        refreshing.clear();
    }
}
