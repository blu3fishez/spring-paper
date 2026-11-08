package org.blu3fishez.springpaper.security.connection.api;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityExchange;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityRequest;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityFilter;
import org.blu3fishez.springpaper.security.connection.internal.ContextClassLoaderScope;
import org.springframework.beans.factory.BeanClassLoaderAware;

/** Immutable ordered chain. The first matching bean is selected, following Spring FilterChainProxy semantics. */
public final class PaperSecurityFilterChain implements BeanClassLoaderAware {
    private final Predicate<PaperSecurityRequest> matcher;
    private final List<PaperSecurityFilter> filters;
    private ClassLoader loader = ContextClassLoaderScope.capture();
    public PaperSecurityFilterChain(Predicate<PaperSecurityRequest> matcher, List<PaperSecurityFilter> filters) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.filters = List.copyOf(filters);
    }
    @Override public void setBeanClassLoader(ClassLoader loader) { this.loader = loader; }
    public boolean matches(PaperSecurityRequest request) { return ContextClassLoaderScope.call(loader, () -> matcher.test(request)); }
    public CompletionStage<PaperSecurityDecision> filter(PaperSecurityExchange exchange) { return invoke(0, exchange); }
    private CompletionStage<PaperSecurityDecision> invoke(int index, PaperSecurityExchange exchange) {
        if (index == filters.size()) return CompletableFuture.completedFuture(PaperSecurityDecision.denied("no_authorization_rule"));
        AtomicBoolean continued = new AtomicBoolean();
        try {
            return ContextClassLoaderScope.call(loader, () -> Objects.requireNonNull(filters.get(index).filter(exchange, next -> {
                if (!continued.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("A filter may continue only once"));
                if (next.request() != exchange.request() || next.session() != exchange.session()) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("A filter must retain request/session identity"));
                }
                return invoke(index + 1, next);
            }), "A filter returned a null stage")).thenApply(decision -> Objects.requireNonNull(decision, "A filter returned a null decision"));
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
}
