package org.blu3fishez.springpaper.examples.approval;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.blu3fishez.springpaper.examples.approval.domain.OnlineIdentityToken;
import org.blu3fishez.springpaper.examples.approval.repository.ApprovalRepository;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.domain.PaperAuthenticationChallenge;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.spi.PaperAuthenticationEntryPoint;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler;
import org.blu3fishez.springpaper.security.service.DefaultPaperConnectionAdmissionHandler;
import org.blu3fishez.springpaper.security.spi.PaperConnectionAdmissionHandler;
import org.blu3fishez.springpaper.security.spi.PaperConnectionAuthenticationConverter;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.*;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Configuration(proxyBeanMethods = false)
@Import(PaperCoreConfiguration.class)
@ComponentScan(basePackageClasses = ApprovalConfiguration.class)
public class ApprovalConfiguration {
    @Bean PaperConnectionAuthenticationConverter onlineIdentityConverter(Server server) {
        // UUID/name alone is not proof. This demo requires Paper itself to verify the Mojang login.
        // An offline Velocity backend needs a converter/provider for its independently verified proof.
        if (!server.getOnlineMode()) throw new IllegalStateException("Approval example requires Paper online-mode=true");
        return event -> new OnlineIdentityToken(event.getUniqueId(), event.getName());
    }
    @Bean AuthenticationProvider onlineIdentityProvider() {
        return new AuthenticationProvider() {
            @Override public boolean supports(Class<?> type) { return OnlineIdentityToken.class.isAssignableFrom(type); }
            @Override public Authentication authenticate(Authentication input) {
                var identity = ((OnlineIdentityToken) input).getPrincipal();
                return UsernamePasswordAuthenticationToken.authenticated(identity, null,
                        List.of(new SimpleGrantedAuthority("ROLE_PLAYER")));
            }
        };
    }
    @Bean PaperSecuritySessionRepository consumerSessionRepository(Clock clock) {
        return new InMemoryPaperSecuritySessionRepository(clock);
    }
    @Bean PaperAuthenticationEntryPoint pendingApproval(Logger logger) {
        return session -> {
            logger.info("Approval required: " + session.connection().playerId());
            return CompletableFuture.completedFuture(null);
        };
    }
    @Bean PaperSecurityResultHandler approvalResults(Logger logger) {
        return (session, failure) -> {
            logger.info("Approval result: " + session.status());
            return CompletableFuture.completedFuture(null);
        };
    }
    @Bean PaperConnectionAdmissionHandler approvalAdmission() {
        // Pending stays outside the world; after console approval the player reconnects with fresh proof.
        return new DefaultPaperConnectionAdmissionHandler("관리자 승인이 필요합니다. 승인 후 다시 접속해 주세요.");
    }
    @Bean PaperSecurityFilterChain approvalChain(AuthenticationManager manager, PaperTasks tasks, ApprovalRepository approvals) {
        return new PaperSecurity().authenticationManager(manager, tasks)
                .addFilter((exchange, next) -> {
                    if (exchange.authentication() == null) return CompletableFuture.completedFuture(PaperSecurityDecision.denied("identity_missing"));
                    if (!approvals.approved(exchange.request().connection().playerId())) {
                        return CompletableFuture.completedFuture(PaperSecurityDecision.pending(
                                new PaperAuthenticationChallenge("operator-approval", Map.of()), exchange.authentication()));
                    }
                    return next.next(exchange);
                })
                .authorize((authentication, exchange) -> new AuthorizationDecision(
                        approvals.approved(exchange.request().connection().playerId())))
                .build();
    }
}
