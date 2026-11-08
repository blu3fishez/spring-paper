package org.blu3fishez.springpaper.security.connection.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.fixture.ManualSecurityTime;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.connection.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.*;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ConsumerConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    static class ConsumerConfiguration {
        @Bean ManualSecurityTime consumerClockAndScheduler() { return new ManualSecurityTime(); }
        @Bean ConsumerRepository consumerRepository(Clock clock) { return new ConsumerRepository(clock); }
        @Bean ConsumerPendingFlow consumerPendingFlow() { return new ConsumerPendingFlow(); }
        @Bean ConsumerResults consumerResults() { return new ConsumerResults(); }
        @Bean AuthenticationProvider consumerProofProvider() {
            return new AuthenticationProvider() {
                @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
                @Override public Authentication authenticate(Authentication input) {
                    var proof = input.getCredentials();
                    if (!"primary".equals(proof) && !"both".equals(proof)) throw new BadCredentialsException("Invalid consumer proof");
                    var authorities = new ArrayList<SimpleGrantedAuthority>();
                    authorities.add(new SimpleGrantedAuthority("PRIMARY"));
                    if ("both".equals(proof)) authorities.add(new SimpleGrantedAuthority("SECONDARY"));
                    return UsernamePasswordAuthenticationToken.authenticated(new ConsumerPrincipal(input.getName()), null, authorities);
                }
            };
        }
        @Bean PaperSecurityFilterChain consumerLoginChain(AuthenticationManager authenticationManager) {
            return new PaperSecurity().authenticationManager(authenticationManager, Runnable::run)
                    .addFilter((exchange, next) -> {
                        var authentication = exchange.authentication();
                        if (authentication != null && authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("SECONDARY"))) {
                            return CompletableFuture.completedFuture(PaperSecurityDecision.pending(
                                    new PaperAuthenticationChallenge("consumer-next-step", Map.of("reference", "opaque")), authentication));
                        }
                        return next.next(exchange);
                    })
                    .authorize((authentication, exchange) -> new AuthorizationDecision(authentication.get().getAuthorities().stream()
                            .map(a -> a.getAuthority()).toList().containsAll(List.of("PRIMARY", "SECONDARY"))))
                    .build();
        }
    }
    record ConsumerPrincipal(String name) {}
    static class ConsumerPendingFlow implements PaperAuthenticationEntryPoint {
        final List<PaperAuthenticationChallenge> challenges = new ArrayList<>();
        @Override public CompletionStage<Void> commence(PaperSecuritySession session) {
            challenges.add(session.challenge()); return CompletableFuture.completedFuture(null);
        }
    }
    static class ConsumerResults implements PaperSecurityResultHandler {
        final List<PaperSecuritySession.Status> results = new ArrayList<>();
        @Override public CompletionStage<Void> handle(PaperSecuritySession session, Throwable failure) {
            results.add(session.status()); return CompletableFuture.completedFuture(null);
        }
    }
    static class ConsumerRepository implements PaperSecuritySessionRepository {
        private final InMemoryPaperSecuritySessionRepository delegate;
        int commits;
        ConsumerRepository(Clock clock) { delegate = new InMemoryPaperSecuritySessionRepository(clock); }
        @Override public CompletionStage<Boolean> create(PaperSecuritySession initial) { return delegate.create(initial); }
        @Override public CompletionStage<Optional<PaperSecuritySession>> find(PaperSecurityConnection connection) { return delegate.find(connection); }
        @Override public CompletionStage<Boolean> compareAndSet(PaperSecuritySession expected, PaperSecuritySession replacement) {
            return delegate.compareAndSet(expected, replacement).thenApply(changed -> { if (changed) commits++; return changed; });
        }
        @Override public CompletionStage<Boolean> remove(PaperSecuritySession expected) { return delegate.remove(expected); }
        @Override public CompletionStage<Optional<PaperSecuritySession>> invalidate(PaperSecurityConnection connection) { return delegate.invalidate(connection); }
    }
    @Configuration(proxyBeanMethods = false)
    static class FirstMatch {
        @Bean @Order(-100) PaperSecurityFilterChain consumerConnectPolicy() {
            return new PaperSecurity().securityMatcher(request -> request.stage() == PaperSecurityRequest.Stage.CONNECT)
                    .addFilter((exchange, next) -> CompletableFuture.completedFuture(PaperSecurityDecision.denied("first_match"))).build();
        }
    }
    private static AnnotationConfigApplicationContext context(Class<?>... extra) {
        var context = new AnnotationConfigApplicationContext();
        context.setAllowBeanDefinitionOverriding(false);
        context.register(ConsumerConfiguration.class, PaperConnectionSecurityConfiguration.class);
        if (extra.length > 0) context.register(extra);
        return context;
    }
    private static PaperSecurityRequest request(PaperSecurityConnection connection, PaperSecurityRequest.Stage stage, String proof) {
        return new PaperSecurityRequest(connection, stage, UsernamePasswordAuthenticationToken.unauthenticated("Consumer", proof));
    }
    @Test void consumerBeansReplaceAllDefaultsAndSupportMultiStepAuthenticationWithoutPaperOnClasspath() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.Bukkit"));
        try (var context = context()) {
            context.refresh();
            assertEquals(1, context.getBeansOfType(PaperSecuritySessionRepository.class).size());
            assertSame(context.getBean(ConsumerRepository.class), context.getBean(PaperSecuritySessionRepository.class));
            assertSame(context.getBean(ConsumerPendingFlow.class), context.getBean(PaperAuthenticationEntryPoint.class));
            assertSame(context.getBean(ConsumerResults.class), context.getBean(PaperSecurityResultHandler.class));
            assertFalse(context.containsBean("paperDefaultSecurityFilterChain"));
            assertInstanceOf(ProviderManager.class, context.getBean(AuthenticationManager.class));
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            var security = context.getBean(PaperConnectionSecurityService.class);
            var first = security.open(request(connection, PaperSecurityRequest.Stage.CONNECT, "primary")).toCompletableFuture().join();
            assertEquals(PaperSecuritySession.Status.PENDING, first.status());
            assertEquals("consumer-next-step", context.getBean(ConsumerPendingFlow.class).challenges.getFirst().type());
            var second = security.authenticate(request(connection, PaperSecurityRequest.Stage.AUTHENTICATE, "both")).toCompletableFuture().join();
            assertEquals(PaperSecuritySession.Status.AUTHENTICATED, second.status());
            assertInstanceOf(ConsumerPrincipal.class, second.authentication().getPrincipal());
            assertNull(second.authentication().getCredentials());
            assertEquals(2, context.getBean(ConsumerRepository.class).commits);
            assertEquals(List.of(PaperSecuritySession.Status.AUTHENTICATED), context.getBean(ConsumerResults.class).results);
        }
    }
    @Test void consumerAuthenticationManagerTakesPrecedenceOverProviderManagerDefault() {
        AuthenticationManager own = input -> UsernamePasswordAuthenticationToken.authenticated("custom-manager", null,
                List.of(new SimpleGrantedAuthority("PRIMARY"), new SimpleGrantedAuthority("SECONDARY")));
        try (var context = context()) {
            context.registerBean("consumerAuthenticationManager", AuthenticationManager.class, () -> own);
            context.refresh();
            assertSame(own, context.getBean(AuthenticationManager.class));
            assertEquals(1, context.getBeansOfType(AuthenticationManager.class).size());
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            var result = context.getBean(PaperConnectionSecurityService.class).open(request(connection, PaperSecurityRequest.Stage.CONNECT, "unused"))
                    .toCompletableFuture().join();
            assertEquals("custom-manager", result.authentication().getName());
        }
    }
    @Test void firstMatchingOrderedChainOwnsTheDecision() {
        try (var context = context(FirstMatch.class)) {
            context.refresh();
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            var result = context.getBean(PaperConnectionSecurityService.class).open(request(connection, PaperSecurityRequest.Stage.CONNECT, "both"))
                    .toCompletableFuture().join();
            assertEquals(PaperSecuritySession.Status.DENIED, result.status());
            assertEquals("first_match", result.reason());
        }
    }
    @Test void invalidProofRemainsDeniedEvenWhenInputTokenClaimsToBeAuthenticated() {
        try (var context = context()) {
            context.refresh();
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            var forged = UsernamePasswordAuthenticationToken.authenticated("Consumer", "invalid", List.of(new SimpleGrantedAuthority("SECONDARY")));
            var result = context.getBean(PaperConnectionSecurityService.class).open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, forged))
                    .toCompletableFuture().join();
            assertEquals(PaperSecuritySession.Status.DENIED, result.status());
            assertNull(result.authentication());
        }
    }
    @Test void defaultSchedulerUsesTheConsumerClockAndUnconfiguredAuthenticationDoesNotGrantAccess() {
        Clock clock = Clock.offset(Clock.systemUTC(), java.time.Duration.ofHours(1));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("consumerClock", Clock.class, () -> clock);
            context.register(PaperConnectionSecurityConfiguration.class);
            context.refresh();
            assertSame(clock, context.getBean(org.springframework.scheduling.TaskScheduler.class).getClock());
            assertThrows(ProviderNotFoundException.class, () -> context.getBean(AuthenticationManager.class).authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated("Consumer", "not-verified")));
        }
    }
    @Test void standaloneCoreUsesItsSpringContextEnvironmentWithoutAPaperConfigurationBean() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("consumer", Map.of(
                    "spring.paper.security.pending-timeout-seconds", 17,
                    "spring.paper.security.operation-timeout-seconds", 7,
                    "spring.paper.security.shutdown-timeout-seconds", 3)));
            context.register(PaperConnectionSecurityConfiguration.class);
            context.refresh();
            var settings = context.getBean(PaperSecuritySettings.class);
            assertEquals(java.time.Duration.ofSeconds(17), settings.pendingTimeout());
            assertEquals(java.time.Duration.ofSeconds(7), settings.operationTimeout());
            assertEquals(java.time.Duration.ofSeconds(3), settings.shutdownTimeout());
        }
    }
}
