package org.blu3fishez.springpaper.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.bootstrap.SpringPaperApplication;
import org.blu3fishez.springpaper.security.api.PaperSecurityConnections;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.listener.PaperConnectionSecurityListener;
import org.blu3fishez.springpaper.security.service.DefaultPaperConnectionAdmissionHandler;
import org.blu3fishez.springpaper.security.spi.PaperConnectionAdmissionHandler;
import org.blu3fishez.springpaper.security.spi.PaperConnectionAuthenticationConverter;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class PaperAdmissionIntegrationTest {
    @SpringPaperPluginApplication(name = "SecurityTest", configurations = ConsumerFeatures.class)
    public static class Host extends JavaPlugin {}
    public static class ProtectedAction {
        @PreAuthorize("hasAuthority('VERIFIED')") public String use() { return "allowed"; }
    }
    @Configuration(proxyBeanMethods = false)
    public static class ConsumerFeatures {
        @Bean public AuthenticationManager consumerManager() {
            return input -> {
                if (!List.of("valid", "pending").contains(input.getCredentials())) throw new BadCredentialsException("Invalid test proof");
                return UsernamePasswordAuthenticationToken.authenticated(input.getName(), null,
                        List.of(new SimpleGrantedAuthority(input.getCredentials().equals("valid") ? "VERIFIED" : "PARTIAL")));
            };
        }
        @Bean public PaperSecurityFilterChain consumerChain(AuthenticationManager manager, PaperTasks tasks) {
            return new PaperSecurity().authenticationManager(manager, tasks).addFilter((exchange, next) -> {
                if (exchange.authentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("PARTIAL"))) {
                    return CompletableFuture.completedFuture(PaperSecurityDecision.pending(new PaperAuthenticationChallenge("custom-pending", Map.of()), exchange.authentication()));
                }
                return next.next(exchange);
            }).authorize((authentication, exchange) -> new AuthorizationDecision(true)).build();
        }
        @Bean public PaperConnectionAuthenticationConverter consumerConverter() {
            // Test fixture only: provider proof is supplied by the synthetic event, never a production hostname verifier.
            return event -> UsernamePasswordAuthenticationToken.unauthenticated(event.getName(), event.getHostname());
        }
        @Bean public ConsumerAdmission consumerAdmission() { return new ConsumerAdmission(); }
        @Bean public ProtectedAction protectedAction() { return new ProtectedAction(); }
    }
    public static class ConsumerAdmission implements PaperConnectionAdmissionHandler {
        boolean allowPending;
        final DefaultPaperConnectionAdmissionHandler fallback = new DefaultPaperConnectionAdmissionHandler("denied");
        @Override public void admit(AsyncPlayerPreLoginEvent event, PaperSecuritySession session) {
            if (!allowPending || session.status() != PaperSecuritySession.Status.PENDING) fallback.admit(event, session);
        }
        @Override public void revoke(Player player, String reason) { fallback.revoke(player, reason); }
    }
    private record Fixture(Host host, Server server, PluginManager manager) {}
    private static Fixture host() {
        Thread main = Thread.currentThread();
        Host host = mock(Host.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(host.getServer()).thenReturn(server);
        when(host.isEnabled()).thenReturn(true);
        when(host.getName()).thenReturn("SecurityTest");
        when(host.namespace()).thenReturn("securitytest");
        when(host.getLogger()).thenReturn(Logger.getLogger("security-test"));
        when(host.getDataFolder()).thenReturn(new java.io.File("build/security-test/absent"));
        when(host.getResource(anyString())).thenAnswer(ignored -> new ByteArrayInputStream(
                "spring:\n  paper:\n    security:\n      enabled: true\n      connection:\n        enabled: true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(server.isPrimaryThread()).thenAnswer(ignored -> Thread.currentThread() == main);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(host), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        return new Fixture(host, server, manager);
    }
    private record Login(AsyncPlayerPreLoginEvent event, PlayerLoginConnection nativeConnection, AtomicReference<AsyncPlayerPreLoginEvent.Result> result) {}
    private static Login login(UUID id, int port, String proof) {
        var event = mock(AsyncPlayerPreLoginEvent.class);
        var transport = mock(PlayerLoginConnection.class);
        var result = new AtomicReference<>(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        when(event.getConnection()).thenReturn(transport);
        when(event.getUniqueId()).thenReturn(id);
        when(event.getName()).thenReturn("Consumer");
        when(event.getAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(event.getHostname()).thenReturn(proof);
        when(event.getLoginResult()).thenAnswer(ignored -> result.get());
        doAnswer(call -> { result.set(call.getArgument(0)); return null; }).when(event).disallow(any(AsyncPlayerPreLoginEvent.Result.class), any(Component.class));
        when(transport.getAddress()).thenReturn(new InetSocketAddress("127.0.0.1", port));
        when(transport.isConnected()).thenReturn(true);
        return new Login(event, transport, result);
    }
    private static Player player(UUID id, Login login) {
        var player = mock(Player.class);
        var connection = mock(PlayerGameConnection.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Consumer");
        when(player.getConnection()).thenReturn(connection);
        when(player.isOnline()).thenReturn(true);
        var address = login.nativeConnection.getAddress();
        when(connection.getAddress()).thenReturn(address);
        return player;
    }

    @Test void actualBootstrapRegistersTheAdapterAndUsesVerifiedSessionForMethodSecurity() throws Exception {
        var fixture = host();
        try (var application = SpringPaperApplication.run(fixture.host)) {
            var context = application.context();
            var listener = context.getBean(PaperConnectionSecurityListener.class);
            verify(fixture.manager).registerEvent(eq(AsyncPlayerPreLoginEvent.class), any(), eq(EventPriority.LOWEST), any(), eq(fixture.host), eq(false));
            var id = UUID.randomUUID();
            var login = login(id, 31001, "valid");
            listener.preLogin(login.event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login.result.get());
            var player = player(id, login);
            listener.joined(new PlayerJoinEvent(player, Component.empty()));
            var interceptor = context.getBean(PaperSecurityInterceptor.class);
            assertEquals("allowed", interceptor.invoke(new PaperInvocation(player, "test", false, PaperInvocation.Kind.COMMAND), context.getBean(ProtectedAction.class)::use));
            verify(player, never()).getEffectivePermissions();
            var connections = context.getBean(PaperSecurityConnections.class);
            var token = connections.connection(player).orElseThrow();
            listener.quit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            assertTrue(context.getBean(PaperConnectionSecurityService.class).session(token).toCompletableFuture().join().isEmpty());
        }
    }
    @Test void customPendingAdmissionAndLaterConsumerCallbackUseTheSameConnection() throws Exception {
        var fixture = host();
        try (var application = SpringPaperApplication.run(fixture.host)) {
            var context = application.context();
            context.getBean(ConsumerAdmission.class).allowPending = true;
            var listener = context.getBean(PaperConnectionSecurityListener.class);
            var id = UUID.randomUUID();
            var login = login(id, 31002, "pending");
            listener.preLogin(login.event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login.result.get());
            var player = player(id, login);
            listener.joined(new PlayerJoinEvent(player, Component.empty()));
            var interceptor = context.getBean(PaperSecurityInterceptor.class);
            var input = new PaperInvocation(player, "test", false, PaperInvocation.Kind.COMMAND);
            assertEquals(true, interceptor.invoke(input, context.getBean(ProtectedAction.class)::use));
            verify(player).sendPlainMessage(anyString());
            var connection = context.getBean(PaperSecurityConnections.class).connection(player).orElseThrow();
            context.getBean(PaperConnectionSecurityService.class).authenticate(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.AUTHENTICATE,
                    UsernamePasswordAuthenticationToken.unauthenticated("Consumer", "valid"))).toCompletableFuture().join();
            assertEquals("allowed", interceptor.invoke(input, context.getBean(ProtectedAction.class)::use));
        }
    }
    @Test void priorHostDenialIsPreservedAndDefaultPendingOrBadProofCannotEnter() {
        var fixture = host();
        try (var application = SpringPaperApplication.run(fixture.host)) {
            var listener = application.context().getBean(PaperConnectionSecurityListener.class);
            var prior = login(UUID.randomUUID(), 31003, "valid");
            prior.result.set(AsyncPlayerPreLoginEvent.Result.KICK_BANNED);
            listener.preLogin(prior.event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, prior.result.get());
            verify(prior.event, never()).getHostname();
            var pending = login(UUID.randomUUID(), 31004, "pending");
            listener.preLogin(pending.event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, pending.result.get());
            var invalid = login(UUID.randomUUID(), 31005, "invalid");
            listener.preLogin(invalid.event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, invalid.result.get());
        }
    }
    @Test void oldPlayerQuitCannotRemoveReplacementConnectionForSameUuid() {
        var fixture = host();
        try (var application = SpringPaperApplication.run(fixture.host)) {
            var context = application.context();
            var listener = context.getBean(PaperConnectionSecurityListener.class);
            var id = UUID.randomUUID();
            var oldLogin = login(id, 31006, "valid");
            listener.preLogin(oldLogin.event);
            var oldPlayer = player(id, oldLogin);
            listener.joined(new PlayerJoinEvent(oldPlayer, Component.empty()));
            var freshLogin = login(id, 31007, "valid");
            listener.preLogin(freshLogin.event);
            var freshPlayer = player(id, freshLogin);
            listener.joined(new PlayerJoinEvent(freshPlayer, Component.empty()));
            listener.quit(new PlayerQuitEvent(oldPlayer, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            var connections = context.getBean(PaperSecurityConnections.class);
            assertTrue(connections.session(freshPlayer).orElseThrow().authorized(java.time.Instant.now()));
            assertTrue(connections.connection(oldPlayer).isEmpty());
        }
    }
    @Test void revocationBetweenAdmissionAndJoinCannotBeUsedToEnterTheWorld() {
        var fixture = host();
        try (var application = SpringPaperApplication.run(fixture.host)) {
            var context = application.context();
            var listener = context.getBean(PaperConnectionSecurityListener.class);
            var id = UUID.randomUUID();
            var login = login(id, 31008, "valid");
            listener.preLogin(login.event);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login.result.get());
            var connections = context.getBean(PaperSecurityConnections.class);
            var token = connections.connection(id, login.nativeConnection.getAddress()).orElseThrow();
            context.getBean(PaperConnectionSecurityService.class).disconnect(token).toCompletableFuture().join();
            var player = player(id, login);
            listener.joined(new PlayerJoinEvent(player, Component.empty()));
            verify(player).kick(any(Component.class));
            assertTrue(connections.connection(player).isEmpty());
        }
    }
}
