package org.blu3fishez.springpaper.security.connection.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.security.connection.api.*;
import org.blu3fishez.springpaper.security.connection.config.*;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.fixture.ManualSecurityTime;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ConnectionFailureTest {
    private static class Repository implements PaperSecuritySessionRepository {
        final InMemoryPaperSecuritySessionRepository storage;
        CompletableFuture<Boolean> createGate;
        CompletableFuture<Optional<PaperSecuritySession>> deleteGate;
        PaperSecuritySession initial;
        Repository(ManualSecurityTime time) { storage = new InMemoryPaperSecuritySessionRepository(time); }
        @Override public CompletionStage<Boolean> create(PaperSecuritySession session) {
            initial = session; return createGate == null ? storage.create(session) : createGate;
        }
        @Override public CompletionStage<Optional<PaperSecuritySession>> find(PaperSecurityConnection connection) { return storage.find(connection); }
        @Override public CompletionStage<Boolean> compareAndSet(PaperSecuritySession expected, PaperSecuritySession replacement) { return storage.compareAndSet(expected, replacement); }
        @Override public CompletionStage<Boolean> remove(PaperSecuritySession expected) { return storage.remove(expected); }
        @Override public CompletionStage<Optional<PaperSecuritySession>> invalidate(PaperSecurityConnection connection) {
            return deleteGate == null ? storage.invalidate(connection) : deleteGate;
        }
    }
    private static PaperSecurityDecision allowed() {
        return PaperSecurityDecision.authenticated(UsernamePasswordAuthenticationToken.authenticated("consumer", null,
                List.of(new SimpleGrantedAuthority("VERIFIED"))));
    }
    private static PaperSecurityConnection connection() { return PaperSecurityConnection.create(UUID.randomUUID(), "Consumer"); }
    private static PaperSecurityRequest request(PaperSecurityConnection connection) { return new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null); }
    private static PaperConnectionSecurityService service(Repository repository, ManualSecurityTime time, PaperSecurityFilter filter,
            PaperSecurityResultHandler results, ApplicationEventPublisher events) {
        return new PaperConnectionSecurityService(repository, List.of(new PaperSecurity().addFilter(filter).build()), time, time,
                new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1)),
                pending -> CompletableFuture.completedFuture(null), results, events);
    }
    @Test void stalledRepositoryDeletionCannotDelayLocalTimeoutAndShutdownTracksTheCleanup() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var events = new ArrayList<Object>();
        var callback = new CompletableFuture<PaperSecurityDecision>();
        try (var security = service(repository, time, (exchange, next) -> callback, (session, failure) -> CompletableFuture.completedFuture(null), events::add)) {
            var connection = connection();
            var result = security.open(request(connection)).toCompletableFuture();
            repository.deleteGate = new CompletableFuture<>();
            time.advance(Duration.ofSeconds(5));
            assertTrue(result.isCompletedExceptionally(), "local timeout must not await remote deletion");
            assertTrue(security.session(connection).toCompletableFuture().join().isEmpty());
            assertTrue(events.stream().anyMatch(event -> event instanceof PaperSecurityConnectionInvalidatedEvent));
            var stopped = security.shutdown().toCompletableFuture();
            assertFalse(stopped.isDone(), "shutdown must retain outstanding cleanup work");
            var removed = repository.storage.invalidate(connection).toCompletableFuture().join();
            repository.deleteGate.complete(removed);
            stopped.join();
            callback.complete(allowed());
            assertTrue(repository.storage.find(connection).toCompletableFuture().join().isEmpty());
        }
    }
    @Test void delayedCreateAfterDisconnectIsRemovedWithoutPublishingAuthentication() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        repository.createGate = new CompletableFuture<>();
        var events = new ArrayList<Object>();
        try (var security = service(repository, time, (exchange, next) -> CompletableFuture.completedFuture(allowed()),
                (session, failure) -> CompletableFuture.completedFuture(null), events::add)) {
            var connection = connection();
            var result = security.open(request(connection)).toCompletableFuture();
            security.disconnect(connection).toCompletableFuture().join();
            assertTrue(result.isCompletedExceptionally());
            assertTrue(repository.storage.create(repository.initial).toCompletableFuture().join());
            repository.createGate.complete(true);
            assertTrue(repository.storage.find(connection).toCompletableFuture().join().isEmpty());
            assertTrue(events.stream().noneMatch(event -> event instanceof PaperSecuritySessionChangedEvent changed
                    && changed.session().status() == PaperSecuritySession.Status.AUTHENTICATED));
        }
    }
    @Test void hangingSuccessHandlerIsRevokedAtOperationDeadlineAndLateHandlerCompletionDoesNotRestoreIt() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var handler = new CompletableFuture<Void>();
        try (var security = service(repository, time, (exchange, next) -> CompletableFuture.completedFuture(allowed()),
                (session, failure) -> session.status() == PaperSecuritySession.Status.AUTHENTICATED ? handler : CompletableFuture.completedFuture(null), event -> {})) {
            var connection = connection();
            var result = security.open(request(connection)).toCompletableFuture();
            assertFalse(result.isDone());
            time.advance(Duration.ofSeconds(5));
            assertTrue(result.isCompletedExceptionally());
            handler.complete(null);
            assertTrue(repository.storage.find(connection).toCompletableFuture().join().isEmpty());
        }
    }
    @Test void rejectedCreateNeverDeletesARecordOwnedByAnotherContext() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var connection = connection();
        var existing = PaperSecuritySession.pending(connection, time.instant().plusSeconds(100));
        assertTrue(repository.storage.create(existing).toCompletableFuture().join());
        try (var security = service(repository, time, (exchange, next) -> CompletableFuture.completedFuture(allowed()),
                (session, failure) -> CompletableFuture.completedFuture(null), event -> {})) {
            assertThrows(Exception.class, () -> security.open(request(connection)).toCompletableFuture().join());
        }
        assertEquals(existing, repository.storage.find(connection).toCompletableFuture().join().orElseThrow());
    }
    @Test void aBrokenObserverDoesNotPreventFailClosedCleanupOrCauseSelfSuppression() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var broken = new IllegalStateException("consumer observer failed");
        try (var security = service(repository, time, (exchange, next) -> CompletableFuture.completedFuture(allowed()),
                (session, failure) -> CompletableFuture.completedFuture(null), event -> { throw broken; })) {
            var connection = connection();
            var failure = assertThrows(Exception.class, () -> security.open(request(connection)).toCompletableFuture().join());
            assertSame(broken, failure.getCause());
            assertTrue(repository.storage.find(connection).toCompletableFuture().join().isEmpty());
        }
    }
    @Test void shutdownRetainsAnImmediatelyFailedRepositoryDeletion() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var security = service(repository, time, (exchange, next) -> CompletableFuture.completedFuture(allowed()),
                (session, failure) -> CompletableFuture.completedFuture(null), event -> {});
        var connection = connection();
        var authenticated = security.open(request(connection)).toCompletableFuture().join();
        var broken = new IllegalStateException("repository unavailable during shutdown");
        repository.deleteGate = CompletableFuture.failedFuture(broken);

        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> security.shutdown().toCompletableFuture().join());
        assertSame(broken, failure.getCause());
        assertFalse(security.current(authenticated), "remote cleanup failure must not undo local revocation");
        assertTrue(repository.storage.find(connection).toCompletableFuture().join().isPresent());
        assertThrows(IllegalStateException.class, security::close);
    }
    @Test void shutdownRetainsAnImmediatelyFailedTerminalHandler() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var broken = new IllegalStateException("terminal transport handler failed");
        var security = service(repository, time, (exchange, next) -> CompletableFuture.completedFuture(allowed()),
                (session, failure) -> session.status() == PaperSecuritySession.Status.CLOSED
                        ? CompletableFuture.failedFuture(broken) : CompletableFuture.completedFuture(null), event -> {});
        var connection = connection();
        security.open(request(connection)).toCompletableFuture().join();

        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> security.shutdown().toCompletableFuture().join());
        assertSame(broken, failure.getCause());
        assertTrue(repository.storage.find(connection).toCompletableFuture().join().isEmpty(),
                "a failed notification must still attempt repository cleanup");
        assertThrows(IllegalStateException.class, security::close);
    }
}
