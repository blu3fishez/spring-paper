package org.blu3fishez.springpaper.example.security.config;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.blu3fishez.springpaper.example.security.domain.ExampleProofToken;
import org.blu3fishez.springpaper.example.security.domain.ExampleSecurityPrincipal;
import org.blu3fishez.springpaper.example.security.service.ExampleChallengeService;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.domain.PaperAuthenticationChallenge;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.*;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Configuration(proxyBeanMethods = false)
public class ExampleSecurityConfiguration {
    @Bean public ExampleChallengeService exampleChallengeService() { return new ExampleChallengeService(); }
    @Bean public AuthenticationProvider exampleProofProvider(ExampleChallengeService challenges) {
        return new AuthenticationProvider() {
            @Override public boolean supports(Class<?> type) { return ExampleProofToken.class.isAssignableFrom(type); }
            @Override public Authentication authenticate(Authentication input) {
                var proof = (ExampleProofToken) input;
                try {
                    if (proof.confirmation() && !challenges.consume(proof)) throw new BadCredentialsException("Confirmation is invalid or stale");
                    var authorities = new ArrayList<SimpleGrantedAuthority>();
                    authorities.add(new SimpleGrantedAuthority("EXAMPLE_PRIMARY"));
                    if (proof.confirmation()) authorities.add(new SimpleGrantedAuthority("EXAMPLE_CONFIRMATION"));
                    return UsernamePasswordAuthenticationToken.authenticated(
                            new ExampleSecurityPrincipal(proof.connection().playerId(), proof.connection().name()), null, authorities);
                } finally { proof.eraseCredentials(); }
            }
        };
    }
    @Bean public PaperSecurityFilterChain exampleConnectionSecurity(AuthenticationManager manager, PaperTasks tasks) {
        return new PaperSecurity()
                // 합성 smoke 요청에만 매칭한다. 실제 접속에는 검증하지 않은 profile/hostname을 인증 증거로 쓰지 않는다.
                .securityMatcher(request -> "true".equals(request.attributes().get("verification-fixture")))
                .authenticationManager(manager, tasks)
                .addFilter((exchange, next) -> {
                    if (exchange.authentication() != null && exchange.authentication().getAuthorities().stream()
                            .noneMatch(authority -> authority.getAuthority().equals("EXAMPLE_CONFIRMATION"))) {
                        return CompletableFuture.completedFuture(PaperSecurityDecision.pending(new PaperAuthenticationChallenge(
                                "example-confirmation", Map.of("confirmation", UUID.randomUUID().toString())), exchange.authentication()));
                    }
                    return next.next(exchange);
                })
                .authorize((authentication, exchange) -> new AuthorizationDecision(authentication.get().getAuthorities().stream()
                        .map(authority -> authority.getAuthority()).toList().containsAll(java.util.List.of("EXAMPLE_PRIMARY", "EXAMPLE_CONFIRMATION"))))
                .build();
    }
}
