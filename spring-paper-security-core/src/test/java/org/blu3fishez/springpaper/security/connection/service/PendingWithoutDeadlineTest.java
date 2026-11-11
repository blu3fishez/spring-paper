package org.blu3fishez.springpaper.security.connection.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.config.PaperSecuritySettings;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.fixture.ManualSecurityTime;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class PendingWithoutDeadlineTest {
    @Test void explicitlyDisabledPendingDeadlineSurvivesTimeAndStillAuthenticatesAndDisconnects() throws Exception {
        var time = new ManualSecurityTime();
        var repository = new InMemoryPaperSecuritySessionRepository(time);
        var chain = new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(
                exchange.request().stage() == PaperSecurityRequest.Stage.CONNECT
                        ? PaperSecurityDecision.pending(new PaperAuthenticationChallenge("consumer", Map.of()), null)
                        : PaperSecurityDecision.authenticated(UsernamePasswordAuthenticationToken.authenticated("Consumer", null, List.of())))).build();
        try (var service = new PaperConnectionSecurityService(repository, List.of(chain), time, new BoundedScheduler(time),
                new PaperSecuritySettings(Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(1)),
                pending -> CompletableFuture.completedFuture(null), (result, failure) -> CompletableFuture.completedFuture(null), event -> {})) {
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            var pending = service.open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null))
                    .toCompletableFuture().get();
            assertEquals(PaperSecuritySession.Status.PENDING, pending.status());
            assertEquals(Instant.MAX, pending.expiresAt());
            time.advance(Duration.ofDays(365));
            assertTrue(service.current(pending));
            assertFalse(pending.authorized(time.instant()));
            assertEquals(pending, service.session(connection).toCompletableFuture().get().orElseThrow());
            var authenticated = service.authenticate(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.AUTHENTICATE, null))
                    .toCompletableFuture().get();
            assertTrue(authenticated.authorized(time.instant()));
            service.disconnect(connection).toCompletableFuture().get();
            assertFalse(service.current(authenticated));
            assertTrue(repository.find(connection).toCompletableFuture().get().isEmpty());
        }
    }

    @Test void disablingPendingDeadlineDoesNotDisableOperationTimeout() {
        var time = new ManualSecurityTime();
        var repository = new InMemoryPaperSecuritySessionRepository(time);
        var never = new CompletableFuture<PaperSecurityDecision>();
        var chain = new PaperSecurity().addFilter((exchange, next) -> never).build();
        try (var service = new PaperConnectionSecurityService(repository, List.of(chain), time, new BoundedScheduler(time),
                new PaperSecuritySettings(Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(1)),
                pending -> CompletableFuture.completedFuture(null), (result, failure) -> CompletableFuture.completedFuture(null), event -> {})) {
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            var operation = service.open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null)).toCompletableFuture();
            time.advance(Duration.ofSeconds(5));
            assertTrue(operation.isCompletedExceptionally());
            assertTrue(repository.find(connection).toCompletableFuture().join().isEmpty());
        }
    }

    private record BoundedScheduler(ManualSecurityTime time) implements TaskScheduler {
        @Override public ScheduledFuture<?> schedule(Runnable task, Instant start) {
            assertNotEquals(Instant.MAX, start, "No-deadline sessions must not overflow a native scheduler delay");
            return time.schedule(task, start);
        }
        @Override public java.time.Clock getClock() { return time; }
        @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant start, Duration period) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) { throw new UnsupportedOperationException(); }
    }
}
