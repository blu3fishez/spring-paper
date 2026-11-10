package org.blu3fishez.springpaper.security.connection.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityConnectionInvalidatedEvent;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.config.PaperSecuritySettings;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityRequest;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.blu3fishez.springpaper.security.connection.fixture.ManualSecurityTime;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ConnectionShutdownRaceTest {
    @Test void shutdownRetainsCleanupStartedByAnInvalidationObserverAfterItsAdmissionSnapshot() {
        var time = new ManualSecurityTime();
        var repository = new InMemoryPaperSecuritySessionRepository(time);
        var connections = new ArrayList<PaperSecurityConnection>();
        var service = new AtomicReference<PaperConnectionSecurityService>();
        var reentered = new AtomicBoolean();
        var delegatedConnection = new AtomicReference<PaperSecurityConnection>();
        var terminalInvoked = new AtomicBoolean();
        var terminalHandler = new CompletableFuture<Void>();
        var security = new PaperConnectionSecurityService(repository,
                List.of(new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(
                        PaperSecurityDecision.authenticated(UsernamePasswordAuthenticationToken.authenticated(
                                "consumer", null, List.of(new SimpleGrantedAuthority("VERIFIED")))))).build()),
                time, time, new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1)),
                pending -> CompletableFuture.completedFuture(null),
                (session, failure) -> {
                    if (session.status() == PaperSecuritySession.Status.CLOSED
                            && session.connection().equals(delegatedConnection.get())) {
                        terminalInvoked.set(true);
                        return terminalHandler;
                    }
                    return CompletableFuture.completedFuture(null);
                },
                event -> {
                    if (event instanceof PaperSecurityConnectionInvalidatedEvent invalidated
                            && reentered.compareAndSet(false, true)) {
                        var other = connections.stream().filter(connection -> !connection.equals(invalidated.connection()))
                                .findFirst().orElseThrow();
                        delegatedConnection.set(other);
                        service.get().disconnect(other);
                    }
                });
        service.set(security);
        for (int i = 0; i < 2; i++) {
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer" + i);
            connections.add(connection);
            security.open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null))
                    .toCompletableFuture().join();
        }

        var shutdown = security.shutdown().toCompletableFuture();

        assertTrue(reentered.get());
        assertTrue(terminalInvoked.get());
        assertFalse(terminalHandler.isDone(), "the other connection's terminal effect is still pending");
        assertFalse(shutdown.isDone(), "shutdown must acknowledge cleanup admitted after the initial snapshot");
        var broken = new IllegalStateException("concurrent terminal handler failed");
        terminalHandler.completeExceptionally(broken);
        assertSame(broken, assertThrows(CompletionException.class, shutdown::join).getCause());
        for (var connection : connections) assertTrue(repository.find(connection).toCompletableFuture().join().isEmpty());
        assertThrows(IllegalStateException.class, security::close);
    }

    @Test void callbacksCanRequestShutdownFromAnotherThreadAndFailureDoesNotSkipOtherConnections() {
        var time = new ManualSecurityTime();
        var repository = new InMemoryPaperSecuritySessionRepository(time);
        var service = new AtomicReference<PaperConnectionSecurityService>();
        var repeatedShutdown = new AtomicReference<CompletableFuture<Void>>();
        var handled = new ArrayList<PaperSecurityConnection>();
        var broken = new IllegalStateException("terminal handler failed");
        var security = new PaperConnectionSecurityService(repository,
                List.of(new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(
                        PaperSecurityDecision.authenticated(UsernamePasswordAuthenticationToken.authenticated(
                                "consumer", null, List.of(new SimpleGrantedAuthority("VERIFIED")))))).build()),
                time, time, new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1)),
                pending -> CompletableFuture.completedFuture(null),
                (session, failure) -> {
                    if (session.status() == PaperSecuritySession.Status.CLOSED) {
                        handled.add(session.connection());
                        throw broken;
                    }
                    return CompletableFuture.completedFuture(null);
                },
                event -> {
                    if (event instanceof PaperSecurityConnectionInvalidatedEvent) {
                        assertDoesNotThrow(() -> CompletableFuture.runAsync(() -> {
                            var repeated = service.get().shutdown().toCompletableFuture();
                            assertFalse(repeated.isDone());
                            repeatedShutdown.set(repeated);
                        }).get(5, TimeUnit.SECONDS), "external callbacks must not hold the admission lock");
                    }
                });
        service.set(security);
        var connections = new ArrayList<PaperSecurityConnection>();
        for (int i = 0; i < 2; i++) {
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer" + i);
            connections.add(connection);
            security.open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null))
                    .toCompletableFuture().join();
        }

        var shutdown = security.shutdown().toCompletableFuture();

        assertSame(broken, assertThrows(CompletionException.class, shutdown::join).getCause());
        assertSame(broken, assertThrows(CompletionException.class, repeatedShutdown.get()::join).getCause());
        assertSame(broken, assertThrows(CompletionException.class, () -> security.shutdown().toCompletableFuture().join()).getCause());
        assertEquals(java.util.Set.copyOf(connections), java.util.Set.copyOf(handled));
        assertEquals(2, handled.size());
        for (var connection : connections) assertTrue(repository.find(connection).toCompletableFuture().join().isEmpty());
        assertThrows(IllegalStateException.class, security::close);
    }
}
