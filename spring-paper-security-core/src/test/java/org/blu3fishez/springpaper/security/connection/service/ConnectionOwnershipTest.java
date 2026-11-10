package org.blu3fishez.springpaper.security.connection.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.config.PaperSecuritySettings;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityRequest;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.blu3fishez.springpaper.security.connection.fixture.ManualSecurityTime;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecuritySessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ConnectionOwnershipTest {
    private static final class Repository implements PaperSecuritySessionRepository {
        final InMemoryPaperSecuritySessionRepository storage;
        CompletableFuture<Boolean> createGate;
        CompletableFuture<Optional<PaperSecuritySession>> deleteGate;
        PaperSecuritySession initial;
        int invalidations;
        Repository(ManualSecurityTime time) { storage = new InMemoryPaperSecuritySessionRepository(time); }
        @Override public CompletionStage<Boolean> create(PaperSecuritySession initial) {
            this.initial = initial;
            return createGate == null ? storage.create(initial) : createGate;
        }
        @Override public CompletionStage<Optional<PaperSecuritySession>> find(PaperSecurityConnection connection) { return storage.find(connection); }
        @Override public CompletionStage<Boolean> compareAndSet(PaperSecuritySession expected, PaperSecuritySession replacement) {
            return storage.compareAndSet(expected, replacement);
        }
        @Override public CompletionStage<Boolean> remove(PaperSecuritySession expected) { return storage.remove(expected); }
        @Override public CompletionStage<Optional<PaperSecuritySession>> invalidate(PaperSecurityConnection connection) {
            invalidations++;
            return deleteGate == null ? storage.invalidate(connection) : deleteGate;
        }
    }

    @Test void repeatedDisconnectBeforeRejectedCreateReplyCannotDeleteAnotherContextsRow() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        repository.createGate = new CompletableFuture<>();
        var existing = existing(repository, time);
        try (var security = service(repository, time, (session, failure) -> CompletableFuture.completedFuture(null))) {
            var opened = security.open(request(existing.connection())).toCompletableFuture();
            security.disconnect(existing.connection()).toCompletableFuture().join();
            security.disconnect(existing.connection()).toCompletableFuture().join();

            assertEquals(existing, repository.find(existing.connection()).toCompletableFuture().join().orElseThrow());
            repository.createGate.complete(false);
            assertTrue(opened.isCompletedExceptionally());
            security.disconnect(existing.connection()).toCompletableFuture().join();
            assertEquals(existing, repository.find(existing.connection()).toCompletableFuture().join().orElseThrow());
            assertEquals(0, repository.invalidations);
        } finally { repository.createGate.complete(false); }
    }

    @Test void rejectedOpenFollowedByTransportClosePreservesAnotherContextsRow() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var existing = existing(repository, time);
        try (var security = service(repository, time, (session, failure) -> CompletableFuture.completedFuture(null))) {
            assertThrows(CompletionException.class, () -> security.open(request(existing.connection())).toCompletableFuture().join());
            security.disconnect(existing.connection()).toCompletableFuture().join();
            assertEquals(existing, repository.find(existing.connection()).toCompletableFuture().join().orElseThrow());
            assertEquals(0, repository.invalidations);
        }
    }

    @Test void failedUncertainCreateFollowedByTransportCloseDoesNotAssumeOwnership() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        repository.createGate = new CompletableFuture<>();
        var existing = existing(repository, time);
        try (var security = service(repository, time, (session, failure) -> CompletableFuture.completedFuture(null))) {
            var opened = security.open(request(existing.connection())).toCompletableFuture();
            var broken = new IllegalStateException("create response lost");
            repository.createGate.completeExceptionally(broken);
            assertSame(broken, assertThrows(CompletionException.class, opened::join).getCause());
            security.disconnect(existing.connection()).toCompletableFuture().join();
            assertEquals(existing, repository.find(existing.connection()).toCompletableFuture().join().orElseThrow());
            assertEquals(0, repository.invalidations);
        }
    }

    @Test void repeatedDisconnectAndShutdownWaitForBothOriginalCleanupAcknowledgements() {
        cleanupAcknowledgementsAreShared(null);
    }

    @Test void repeatedDisconnectAndShutdownShareTheOriginalTerminalFailure() {
        cleanupAcknowledgementsAreShared(new IllegalStateException("transport cleanup failed"));
    }

    private static void cleanupAcknowledgementsAreShared(Throwable failure) {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var terminal = new CompletableFuture<Void>();
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        var security = service(repository, time, (session, cause) -> {
            if (session.status() == PaperSecuritySession.Status.CLOSED) { notifications.incrementAndGet(); return terminal; }
            return CompletableFuture.completedFuture(null);
        });
        var connection = connection();
        var authenticated = security.open(request(connection)).toCompletableFuture().join();
        repository.deleteGate = new CompletableFuture<>();
        var first = security.disconnect(connection).toCompletableFuture();
        var repeated = security.disconnect(connection).toCompletableFuture();
        var shutdown = security.shutdown().toCompletableFuture();
        try {
            assertFalse(security.current(authenticated));
            var removed = repository.storage.invalidate(connection).toCompletableFuture().join();
            repository.deleteGate.complete(removed);
            assertFalse(first.isDone());
            assertFalse(repeated.isDone(), "a repeated request must also wait for the original terminal handler");
            assertFalse(shutdown.isDone());
            if (failure == null) {
                terminal.complete(null);
                first.join(); repeated.join(); shutdown.join();
            } else {
                terminal.completeExceptionally(failure);
                for (var result : List.of(first, repeated, shutdown)) {
                    assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
                }
            }
            assertEquals(1, repository.invalidations);
            assertEquals(1, notifications.get());
            assertTrue(repository.find(connection).toCompletableFuture().join().isEmpty());
            if (failure == null) security.close(); else assertThrows(IllegalStateException.class, security::close);
        } finally {
            terminal.complete(null);
            repository.deleteGate.complete(Optional.empty());
            try { security.close(); } catch (IllegalStateException ignored) { /* Failure is asserted above. */ }
        }
    }

    @Test void unknownLocalDisconnectPreservesRemoteRowAndExplicitRepositoryInvalidationStillWorks() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        var external = existing(repository, time);
        var another = PaperSecuritySession.pending(PaperSecurityConnection.create(external.connection().playerId(), "Other"),
                time.instant().plusSeconds(100));
        assertTrue(repository.storage.create(another).toCompletableFuture().join());
        try (var security = service(repository, time, (session, failure) -> CompletableFuture.completedFuture(null))) {
            security.disconnect(external.connection()).toCompletableFuture().join();
            assertEquals(external, repository.find(external.connection()).toCompletableFuture().join().orElseThrow());
            assertEquals(external, repository.invalidate(external.connection()).toCompletableFuture().join().orElseThrow());
            assertTrue(repository.find(external.connection()).toCompletableFuture().join().isEmpty());
            assertEquals(another, repository.find(another.connection()).toCompletableFuture().join().orElseThrow());
        }
    }

    @Test void latePositiveCreateCleanupRemainsTrackedAfterTheEarlierTerminalHandlerCompletes() {
        var time = new ManualSecurityTime();
        var repository = new Repository(time);
        repository.createGate = new CompletableFuture<>();
        var terminal = new CompletableFuture<Void>();
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        var security = service(repository, time, (session, failure) -> {
            notifications.incrementAndGet(); return terminal;
        });
        var connection = connection();
        var opened = security.open(request(connection)).toCompletableFuture();
        var disconnected = security.disconnect(connection).toCompletableFuture();
        try {
            assertTrue(opened.isCompletedExceptionally());
            assertTrue(repository.storage.create(repository.initial).toCompletableFuture().join());
            repository.deleteGate = new CompletableFuture<>();
            repository.createGate.complete(true);
            var shutdown = security.shutdown().toCompletableFuture();
            terminal.complete(null);
            disconnected.join();
            var repeated = security.disconnect(connection).toCompletableFuture();
            assertFalse(repeated.isDone(), "finishing the older handler must retain the newer late-create deletion ACK");
            assertFalse(shutdown.isDone());
            repository.deleteGate.complete(repository.storage.invalidate(connection).toCompletableFuture().join());
            repeated.join(); shutdown.join();
            assertEquals(1, repository.invalidations);
            assertEquals(1, notifications.get(), "the late create must not publish a second terminal result");
            assertTrue(repository.find(connection).toCompletableFuture().join().isEmpty());
        } finally {
            terminal.complete(null);
            if (repository.deleteGate != null) repository.deleteGate.complete(Optional.empty());
            security.close();
        }
    }

    private static PaperSecuritySession existing(Repository repository, ManualSecurityTime time) {
        var existing = PaperSecuritySession.pending(connection(), time.instant().plusSeconds(100));
        assertTrue(repository.storage.create(existing).toCompletableFuture().join());
        return existing;
    }
    private static PaperConnectionSecurityService service(Repository repository, ManualSecurityTime time, PaperSecurityResultHandler handler) {
        var chain = new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(
                PaperSecurityDecision.authenticated(UsernamePasswordAuthenticationToken.authenticated("consumer", null, List.of())))).build();
        return new PaperConnectionSecurityService(repository, List.of(chain), time, time,
                new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1)),
                pending -> CompletableFuture.completedFuture(null), handler, event -> {});
    }
    private static PaperSecurityConnection connection() { return PaperSecurityConnection.create(UUID.randomUUID(), "Consumer"); }
    private static PaperSecurityRequest request(PaperSecurityConnection connection) {
        return new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null);
    }
}
