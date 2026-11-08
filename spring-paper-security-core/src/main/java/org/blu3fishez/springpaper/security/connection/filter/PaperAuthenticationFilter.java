package org.blu3fishez.springpaper.security.connection.filter;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityExchange;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityFilter;
import org.blu3fishez.springpaper.security.connection.internal.ContextClassLoaderScope;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;

/** Uses the consumer's Spring AuthenticationManager on its chosen executor. */
public final class PaperAuthenticationFilter implements PaperSecurityFilter {
    private final AuthenticationManager manager;
    private final Executor executor;
    public PaperAuthenticationFilter(AuthenticationManager manager, Executor executor) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.executor = Objects.requireNonNull(executor, "executor");
    }
    @Override public CompletionStage<PaperSecurityDecision> filter(PaperSecurityExchange exchange, Chain next) {
        if (exchange.request().credentials() == null) return next.next(exchange);
        // ProviderManager는 첫 성공에서 멈춘다. 여러 인증을 모두 요구하는 정책은 뒤의 인가 단계에서 구성한다.
        // 입력 token의 isAuthenticated 플래그도 그대로 신뢰하지 않고 반드시 consumer manager를 통과시킨다.
        var loader = ContextClassLoaderScope.capture();
        return CompletableFuture.supplyAsync(() -> ContextClassLoaderScope.call(loader, () -> {
            var result = manager.authenticate(exchange.request().credentials());
            if (result == null || !result.isAuthenticated()) throw new AuthenticationServiceException("AuthenticationManager did not authenticate");
            if (result instanceof CredentialsContainer credentials) credentials.eraseCredentials();
            return result;
        }), executor).thenCompose(authentication -> next.next(exchange.withAuthentication(authentication)));
    }
}
