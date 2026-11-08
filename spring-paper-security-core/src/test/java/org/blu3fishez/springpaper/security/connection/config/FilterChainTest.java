package org.blu3fishez.springpaper.security.connection.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.AlreadyBuiltException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class FilterChainTest {
    private static class Recording implements PaperSecurityFilter {
        final List<String> calls; final String name;
        Recording(List<String> calls, String name) { this.calls = calls; this.name = name; }
        @Override public CompletionStage<PaperSecurityDecision> filter(PaperSecurityExchange exchange, Chain next) {
            calls.add(name); return next.next(exchange);
        }
    }
    private static class First extends Recording { First(List<String> calls) { super(calls, "first"); } }
    private static class Last extends Recording { Last(List<String> calls) { super(calls, "last"); } }
    private static PaperSecurityExchange exchange() {
        var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
        var authentication = UsernamePasswordAuthenticationToken.authenticated("Consumer", null, List.of(new SimpleGrantedAuthority("VERIFIED")));
        return new PaperSecurityExchange(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null),
                PaperSecuritySession.pending(connection, Instant.MAX), authentication);
    }
    @Test void orderedInsertionAndNativeOneBuildContractArePreserved() {
        var calls = new ArrayList<String>();
        var builder = new PaperSecurity().addFilter(new First(calls)).addFilter(new Last(calls))
                .addFilterAfter(new Recording(calls, "after"), First.class)
                .addFilterBefore(new Recording(calls, "before"), Last.class)
                .authorize((authentication, request) -> new AuthorizationDecision(true));
        var result = builder.build().filter(exchange()).toCompletableFuture().join();
        assertEquals(List.of("first", "after", "before", "last"), calls);
        assertEquals(PaperSecurityDecision.Status.AUTHENTICATED, result.status());
        assertThrows(AlreadyBuiltException.class, builder::build);
        assertThrows(IllegalStateException.class, () -> builder.addFilter(new First(calls)));
    }
    @Test void consumerFilterCanShortCircuitIntoItsOwnPendingFlow() {
        var calls = new ArrayList<String>();
        var chain = new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(
                PaperSecurityDecision.pending(new PaperAuthenticationChallenge("custom", Map.of()), exchange.authentication())))
                .addFilter(new First(calls)).build();
        assertEquals(PaperSecurityDecision.Status.PENDING, chain.filter(exchange()).toCompletableFuture().join().status());
        assertTrue(calls.isEmpty());
    }
    @Test void missingAndAmbiguousAnchorsFailDuringConsumerConfiguration() {
        var calls = new ArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> new PaperSecurity().addFilterBefore(new Recording(calls, "new"), First.class));
        assertThrows(IllegalArgumentException.class, () -> new PaperSecurity().addFilter(new First(calls)).addFilter(new First(calls))
                .addFilterAfter(new Recording(calls, "new"), First.class));
    }
    @Test void aFilterCannotContinueTwiceOrSwapTheRequestConnection() {
        var duplicate = new PaperSecurity().addFilter((exchange, next) -> next.next(exchange).thenCompose(ignored -> next.next(exchange)))
                .authorize((authentication, exchange) -> new AuthorizationDecision(true)).build();
        assertThrows(Exception.class, () -> duplicate.filter(exchange()).toCompletableFuture().join());
        var replaced = new PaperSecurity().addFilter((exchange, next) -> next.next(exchange()))
                .authorize((authentication, exchange) -> new AuthorizationDecision(true)).build();
        assertThrows(Exception.class, () -> replaced.filter(exchange()).toCompletableFuture().join());
    }
}
