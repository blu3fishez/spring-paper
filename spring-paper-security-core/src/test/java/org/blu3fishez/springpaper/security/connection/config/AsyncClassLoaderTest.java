package org.blu3fishez.springpaper.security.connection.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.fixture.ManualSecurityTime;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AsyncClassLoaderTest {
    @Test void foreignCompletionThreadDoesNotChangeFilterProviderOrHandlerClassLoader() {
        ClassLoader owner = Thread.currentThread().getContextClassLoader();
        ClassLoader foreign = new ClassLoader(null) {};
        var completion = new CompletableFuture<Void>();
        var chain = new PaperSecurity().addFilter((exchange, next) -> completion.thenCompose(ignored -> next.next(exchange)))
                .authenticationManager(input -> {
                    assertSame(owner, Thread.currentThread().getContextClassLoader());
                    return UsernamePasswordAuthenticationToken.authenticated("consumer", "must-be-erased", List.of(new SimpleGrantedAuthority("VERIFIED")));
                }, action -> {
                    var previous = Thread.currentThread().getContextClassLoader();
                    Thread.currentThread().setContextClassLoader(foreign);
                    try { action.run(); assertSame(foreign, Thread.currentThread().getContextClassLoader()); }
                    finally { Thread.currentThread().setContextClassLoader(previous); }
                })
                .addFilter((exchange, next) -> {
                    assertSame(owner, Thread.currentThread().getContextClassLoader());
                    return next.next(exchange);
                })
                .authorize((authentication, exchange) -> new AuthorizationDecision(true)).build();
        var time = new ManualSecurityTime();
        var repository = new InMemoryPaperSecuritySessionRepository(time);
        try (var security = new PaperConnectionSecurityService(repository, List.of(chain), time, time,
                new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1)),
                pending -> CompletableFuture.completedFuture(null), (session, failure) -> {
                    assertSame(owner, Thread.currentThread().getContextClassLoader());
                    return CompletableFuture.completedFuture(null);
                }, event -> assertSame(owner, Thread.currentThread().getContextClassLoader()))) {
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            var result = security.open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT,
                    UsernamePasswordAuthenticationToken.unauthenticated("consumer", "input")));
            Thread.currentThread().setContextClassLoader(foreign);
            try {
                completion.complete(null);
                assertSame(foreign, Thread.currentThread().getContextClassLoader());
                var session = result.toCompletableFuture().join();
                assertEquals(PaperSecuritySession.Status.AUTHENTICATED, session.status());
                assertNull(session.authentication().getCredentials());
            } finally { Thread.currentThread().setContextClassLoader(owner); }
        }
    }
}
