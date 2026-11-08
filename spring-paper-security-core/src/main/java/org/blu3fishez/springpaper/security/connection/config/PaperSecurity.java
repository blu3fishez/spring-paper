package org.blu3fishez.springpaper.security.connection.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityExchange;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityRequest;
import org.blu3fishez.springpaper.security.connection.filter.PaperAuthenticationFilter;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityFilter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.AbstractSecurityBuilder;

/** Consumer @Bean builder; uses Spring's one-build contract without introducing a Servlet dependency. */
public final class PaperSecurity extends AbstractSecurityBuilder<PaperSecurityFilterChain> {
    private final List<PaperSecurityFilter> filters = new ArrayList<>();
    private Predicate<PaperSecurityRequest> matcher = request -> true;
    private AuthorizationManager<PaperSecurityExchange> authorization;
    private boolean sealed;
    public PaperSecurity securityMatcher(Predicate<PaperSecurityRequest> matcher) {
        mutable(); this.matcher = Objects.requireNonNull(matcher); return this;
    }
    public PaperSecurity addFilter(PaperSecurityFilter filter) {
        mutable(); filters.add(Objects.requireNonNull(filter)); return this;
    }
    public PaperSecurity addFilterBefore(PaperSecurityFilter filter, Class<? extends PaperSecurityFilter> anchor) {
        mutable(); filters.add(anchor(anchor), Objects.requireNonNull(filter)); return this;
    }
    public PaperSecurity addFilterAfter(PaperSecurityFilter filter, Class<? extends PaperSecurityFilter> anchor) {
        mutable(); filters.add(anchor(anchor) + 1, Objects.requireNonNull(filter)); return this;
    }
    public PaperSecurity authenticationManager(AuthenticationManager manager, Executor executor) {
        return addFilter(new PaperAuthenticationFilter(manager, executor));
    }
    public PaperSecurity authorize(AuthorizationManager<PaperSecurityExchange> authorization) {
        mutable(); this.authorization = Objects.requireNonNull(authorization); return this;
    }
    public PaperSecurity with(Customizer<PaperSecurity> customizer) {
        mutable(); customizer.customize(this); return this;
    }
    @Override protected PaperSecurityFilterChain doBuild() {
        sealed = true;
        var assembled = new ArrayList<>(filters);
        if (authorization != null) assembled.add((exchange, next) -> {
            var authentication = exchange.authentication();
            if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
                return CompletableFuture.completedFuture(PaperSecurityDecision.denied("authentication_required"));
            }
            var result = authorization.authorize(() -> authentication, exchange);
            return CompletableFuture.completedFuture(result != null && result.isGranted()
                    ? PaperSecurityDecision.authenticated(authentication) : PaperSecurityDecision.denied("access_denied"));
        });
        // 빈 체인이나 매칭되지 않은 요청을 허용하면 새 입장 경로가 인증을 우회할 수 있다.
        // 기본 terminal은 거절이다. 소비자가 명시한 인가 규칙 또는 terminal filter만 허용을 결정한다.
        return new PaperSecurityFilterChain(matcher, assembled);
    }
    private int anchor(Class<? extends PaperSecurityFilter> type) {
        int found = -1;
        for (int i = 0; i < filters.size(); i++) {
            if (!type.isInstance(filters.get(i))) continue;
            if (found != -1) throw new IllegalArgumentException("Ambiguous filter anchor: " + type.getName());
            found = i;
        }
        if (found == -1) throw new IllegalArgumentException("Filter anchor has not been added: " + type.getName());
        return found;
    }
    private void mutable() { if (sealed) throw new IllegalStateException("Security has already been built"); }
}
