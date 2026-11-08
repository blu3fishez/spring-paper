package org.blu3fishez.springpaper.security.connection.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.api.PaperSecuritySessionChangedEvent;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.config.PaperSecuritySettings;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.fixture.ManualSecurityTime;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.spi.PaperAuthenticationEntryPoint;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ConnectionSecurityTest {
    private static Authentication authenticated(String name, String... authorities) {
        return UsernamePasswordAuthenticationToken.authenticated(name, null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(2, TimeUnit.SECONDS); }
    private static PaperSecurityConnection connection() { return PaperSecurityConnection.create(UUID.randomUUID(), "Consumer"); }
    private static PaperSecurityRequest request(PaperSecurityConnection connection, PaperSecurityRequest.Stage stage) {
        return new PaperSecurityRequest(connection, stage, null);
    }
    private static final class Harness implements AutoCloseable {
        final ManualSecurityTime time = new ManualSecurityTime();
        final InMemoryPaperSecuritySessionRepository repository = new InMemoryPaperSecuritySessionRepository(time);
        final List<PaperSecuritySession> events = new ArrayList<>();
        final PaperConnectionSecurityService service;
        Harness(PaperSecurityFilterChain chain) {
            this(chain, pending -> CompletableFuture.completedFuture(null), (result, failure) -> CompletableFuture.completedFuture(null));
        }
        Harness(PaperSecurityFilterChain chain, PaperAuthenticationEntryPoint entry, PaperSecurityResultHandler results) {
            service = new PaperConnectionSecurityService(repository, List.of(chain), time, time,
                    new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    entry, results, event -> {
                        if (event instanceof PaperSecuritySessionChangedEvent changed) events.add(changed.session());
                    });
        }
        @Override public void close() { service.close(); }
    }

    @Test void consumerPendingFlowResumesIntoAuthenticatedSessionWithoutThreadLocalMutation() throws Exception {
        var pending = new ArrayList<PaperSecuritySession>();
        var completed = new ArrayList<PaperSecuritySession.Status>();
        var challenge = new PaperAuthenticationChallenge("consumer-browser", Map.of("reference", "opaque"));
        var chain = new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(
                exchange.request().stage() == PaperSecurityRequest.Stage.CONNECT
                        ? PaperSecurityDecision.pending(challenge, authenticated("Consumer", "PRIMARY"))
                        : PaperSecurityDecision.authenticated(authenticated("Consumer", "PRIMARY", "SECONDARY")))).build();
        var previous = SecurityContextHolder.getContext();
        try (var harness = new Harness(chain, session -> { pending.add(session); return CompletableFuture.completedFuture(null); },
                (session, failure) -> { completed.add(session.status()); return CompletableFuture.completedFuture(null); })) {
            var connection = connection();
            var first = await(harness.service.open(request(connection, PaperSecurityRequest.Stage.CONNECT)));
            assertEquals(PaperSecuritySession.Status.PENDING, first.status());
            assertFalse(first.authorized(harness.time.instant()));
            assertEquals(challenge, pending.getFirst().challenge());
            var second = await(harness.service.authenticate(request(connection, PaperSecurityRequest.Stage.AUTHENTICATE)));
            assertTrue(second.authorized(harness.time.instant()));
            assertEquals(2, second.authentication().getAuthorities().size());
            assertEquals(List.of(PaperSecuritySession.Status.AUTHENTICATED), completed);
            assertEquals(second, await(harness.repository.find(connection)).orElseThrow());
            assertSame(previous, SecurityContextHolder.getContext());
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void anAsyncPendingCompletionCanImmediatelyComposeTheNextAuthentication() throws Exception {
        var firstCompletion = new CompletableFuture<PaperSecurityDecision>();
        var chain = new PaperSecurity().addFilter((exchange, next) -> exchange.request().stage() == PaperSecurityRequest.Stage.CONNECT
                ? firstCompletion : CompletableFuture.completedFuture(PaperSecurityDecision.authenticated(authenticated("Consumer", "VERIFIED")))).build();
        try (var harness = new Harness(chain)) {
            var connection = connection();
            var flow = harness.service.open(request(connection, PaperSecurityRequest.Stage.CONNECT))
                    .thenCompose(pending -> harness.service.authenticate(request(connection, PaperSecurityRequest.Stage.AUTHENTICATE)));
            firstCompletion.complete(PaperSecurityDecision.pending(new PaperAuthenticationChallenge("consumer", Map.of()), null));
            assertTrue(await(flow).authorized(harness.time.instant()));
        }
    }

    @Test void disconnectRejectsLateCallbacksWithoutTouchingAnotherConnectionForTheSamePlayer() throws Exception {
        var callbacks = new ConcurrentHashMap<UUID, CompletableFuture<PaperSecurityDecision>>();
        var chain = new PaperSecurity().addFilter((exchange, next) -> callbacks.computeIfAbsent(exchange.request().connection().connectionId(), ignored -> new CompletableFuture<>())).build();
        try (var harness = new Harness(chain)) {
            var old = connection();
            var oldResult = harness.service.open(request(old, PaperSecurityRequest.Stage.CONNECT));
            await(harness.service.disconnect(old));
            var fresh = PaperSecurityConnection.create(old.playerId(), old.name());
            var freshResult = harness.service.open(request(fresh, PaperSecurityRequest.Stage.CONNECT));
            callbacks.get(fresh.connectionId()).complete(PaperSecurityDecision.authenticated(authenticated("fresh", "VERIFIED")));
            assertEquals("fresh", await(freshResult).authentication().getName());
            callbacks.get(old.connectionId()).complete(PaperSecurityDecision.authenticated(authenticated("stale", "VERIFIED")));
            assertThrows(Exception.class, () -> await(oldResult));
            assertTrue(await(harness.repository.find(old)).isEmpty());
            assertEquals("fresh", await(harness.service.session(fresh)).orElseThrow().authentication().getName());
        }
    }

    @Test void pendingDeadlineDoesNotRenewOnRepeatedChallenges() throws Exception {
        var challenge = new PaperAuthenticationChallenge("custom", Map.of());
        var chain = new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(PaperSecurityDecision.pending(challenge, null))).build();
        try (var harness = new Harness(chain)) {
            var connection = connection();
            var original = await(harness.service.open(request(connection, PaperSecurityRequest.Stage.CONNECT)));
            harness.time.advance(Duration.ofSeconds(20));
            var retried = await(harness.service.authenticate(request(connection, PaperSecurityRequest.Stage.AUTHENTICATE)));
            assertEquals(original.expiresAt(), retried.expiresAt());
            harness.time.advance(Duration.ofSeconds(10));
            assertTrue(await(harness.service.session(connection)).isEmpty());
            assertEquals(1, harness.events.stream().filter(event -> event.status() == PaperSecuritySession.Status.EXPIRED).count());
            assertThrows(Exception.class, () -> await(harness.service.authenticate(request(connection, PaperSecurityRequest.Stage.AUTHENTICATE))));
        }
    }

    @Test void operationDeadlineInvalidatesSessionAndLateSuccessCannotReviveIt() throws Exception {
        var callback = new CompletableFuture<PaperSecurityDecision>();
        try (var harness = new Harness(new PaperSecurity().addFilter((exchange, next) -> callback).build())) {
            var connection = connection();
            var result = harness.service.open(request(connection, PaperSecurityRequest.Stage.CONNECT));
            harness.time.advance(Duration.ofSeconds(5));
            assertThrows(Exception.class, () -> await(result));
            callback.complete(PaperSecurityDecision.authenticated(authenticated("late", "VERIFIED")));
            assertTrue(await(harness.repository.find(connection)).isEmpty());
            assertTrue(harness.events.stream().noneMatch(event -> event.status() == PaperSecuritySession.Status.AUTHENTICATED));
        }
    }

    @Test void overlappingAttemptsAreRejectedWithoutCancellingTheOwningAttempt() throws Exception {
        var resumed = new CompletableFuture<PaperSecurityDecision>();
        var chain = new PaperSecurity().addFilter((exchange, next) -> exchange.request().stage() == PaperSecurityRequest.Stage.CONNECT
                ? CompletableFuture.completedFuture(PaperSecurityDecision.pending(new PaperAuthenticationChallenge("custom", Map.of()), null)) : resumed).build();
        try (var harness = new Harness(chain)) {
            var connection = connection();
            await(harness.service.open(request(connection, PaperSecurityRequest.Stage.CONNECT)));
            var first = harness.service.authenticate(request(connection, PaperSecurityRequest.Stage.AUTHENTICATE));
            assertThrows(Exception.class, () -> await(harness.service.authenticate(request(connection, PaperSecurityRequest.Stage.AUTHENTICATE))));
            resumed.complete(PaperSecurityDecision.authenticated(authenticated("winner", "VERIFIED")));
            assertEquals("winner", await(first).authentication().getName());
            assertTrue(await(harness.service.session(connection)).orElseThrow().authorized(harness.time.instant()));
        }
    }

    @Test void resultHandlerFailureRevokesCommittedAuthentication() throws Exception {
        var chain = new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(PaperSecurityDecision.authenticated(authenticated("Consumer", "VERIFIED")))).build();
        try (var harness = new Harness(chain, session -> CompletableFuture.completedFuture(null), (session, failure) ->
                session.status() == PaperSecuritySession.Status.AUTHENTICATED ? CompletableFuture.failedFuture(new IllegalStateException("consumer failure")) : CompletableFuture.completedFuture(null))) {
            var connection = connection();
            assertThrows(Exception.class, () -> await(harness.service.open(request(connection, PaperSecurityRequest.Stage.CONNECT))));
            assertTrue(await(harness.repository.find(connection)).isEmpty());
            assertEquals(PaperSecuritySession.Status.DENIED, harness.events.getLast().status());
        }
    }

    @Test void emptyChainDeniesAndShutdownRejectsFurtherAuthentication() throws Exception {
        try (var harness = new Harness(new PaperSecurity().build())) {
            var connection = connection();
            var result = await(harness.service.open(request(connection, PaperSecurityRequest.Stage.CONNECT)));
            assertEquals(PaperSecuritySession.Status.DENIED, result.status());
            assertEquals("no_authorization_rule", result.reason());
            await(harness.service.shutdown());
            assertTrue(await(harness.repository.find(connection)).isEmpty());
            assertThrows(Exception.class, () -> await(harness.service.open(request(connection(), PaperSecurityRequest.Stage.CONNECT))));
        }
    }

    @Test void repositoryCasRejectsExpiredOrStaleRevision() throws Exception {
        var time = new ManualSecurityTime();
        var repository = new InMemoryPaperSecuritySessionRepository(time);
        var connection = connection();
        var original = PaperSecuritySession.pending(connection, time.instant().plusSeconds(1));
        assertTrue(await(repository.create(original)));
        var authorized = new PaperSecuritySession(connection, 1, PaperSecuritySession.Status.AUTHENTICATED,
                authenticated("Consumer", "VERIFIED"), null, java.time.Instant.MAX, "authenticated");
        time.advance(Duration.ofSeconds(1));
        assertFalse(await(repository.compareAndSet(original, authorized)));
        assertFalse(await(repository.remove(authorized)));
        assertTrue(await(repository.remove(original)));
    }
}
