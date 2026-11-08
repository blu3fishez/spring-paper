package org.blu3fishez.springpaper.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.config.PaperSecuritySettings;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityRequest;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.blu3fishez.springpaper.security.api.PaperSecurityConnections;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityConnectionInvalidatedEvent;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.blu3fishez.springpaper.security.service.PaperSessionAuthenticationResolver;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ConnectionSnapshotTest {
    @Test void asyncInvocationUsesSnapshotAndExpiryOrInvalidationCannotBeUndoneByLateRefresh() {
        var now = new AtomicReference<>(Instant.now());
        Clock clock = new Clock() {
            @Override public Instant instant() { return now.get(); }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        };
        var connections = new PaperSecurityConnections(clock, Duration.ofSeconds(2));
        var nativeLogin = mock(PlayerLoginConnection.class);
        var nativeGame = mock(PlayerGameConnection.class);
        var address = new InetSocketAddress("127.0.0.1", 32001);
        when(nativeLogin.getAddress()).thenReturn(address);
        when(nativeGame.getAddress()).thenReturn(address);
        var player = mock(Player.class);
        var id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getConnection()).thenReturn(nativeGame);
        var connection = connections.begin(id, "Consumer", nativeLogin);
        assertTrue(connections.attach(player));
        var authentication = UsernamePasswordAuthenticationToken.authenticated("verified-consumer", null,
                List.of(new SimpleGrantedAuthority("VERIFIED")));
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setClock(clock);
        scheduler.setDaemon(true);
        scheduler.initialize();
        var chain = new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(
                PaperSecurityDecision.authenticated(authentication))).build();
        try (var security = new PaperConnectionSecurityService(new InMemoryPaperSecuritySessionRepository(clock), List.of(chain), clock, scheduler,
                new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(1)),
                pending -> CompletableFuture.completedFuture(null), (result, failure) -> CompletableFuture.completedFuture(null), event -> {})) {
            var authorized = security.open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null)).toCompletableFuture().join();
            connections.update(authorized);
            clearInvocations(player, nativeLogin, nativeGame);
            var resolver = new PaperSessionAuthenticationResolver(connections, clock, () -> security);
            var async = new PaperInvocation(player, "async-event", true, PaperInvocation.Kind.EVENT);
            assertSame(authentication, resolver.resolve(async));
            verifyNoInteractions(player, nativeLogin, nativeGame);
            now.set(now.get().plusSeconds(2));
            assertNull(resolver.resolve(async));
            connections.update(authorized);
            assertSame(authentication, resolver.resolve(async));
            // Simulate a consumer event publisher that never delivers invalidation to the Paper snapshot.
            security.disconnect(connection).toCompletableFuture().join();
            assertTrue(connections.session(player).isPresent());
            assertNull(resolver.resolve(async), "the core fence must reject even an unchanged cached authentication");
            var deferred = new java.util.ArrayList<Runnable>();
            var multicaster = new org.springframework.context.event.SimpleApplicationEventMulticaster();
            multicaster.setTaskExecutor(deferred::add);
            multicaster.addApplicationListener(connections);
            multicaster.multicastEvent(new org.springframework.context.PayloadApplicationEvent<>(this,
                    new PaperSecurityConnectionInvalidatedEvent(connection)));
            assertTrue(deferred.isEmpty(), "critical snapshot invalidation must not be deferred by an async multicaster");
            connections.update(new PaperSecuritySession(connection, 2, PaperSecuritySession.Status.AUTHENTICATED,
                    authentication, null, Instant.MAX, "late"));
            assertNull(resolver.resolve(async));
        } finally { scheduler.shutdown(); }
    }
}
