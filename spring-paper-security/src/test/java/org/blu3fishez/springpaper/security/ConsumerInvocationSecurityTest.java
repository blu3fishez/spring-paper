package org.blu3fishez.springpaper.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.security.spi.PaperAccessDeniedHandler;
import org.blu3fishez.springpaper.security.spi.PaperAuthenticationResolver;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ConsumerInvocationSecurityTest {
    public static class ProtectedConsumerService {
        @PreAuthorize("hasAuthority('CONSUMER_VERIFIED')") public String execute() { return "consumer-allowed"; }
    }
    record ConsumerIdentity(String accountId) {}
    @Test void consumerResolverAndDenialHandlerReplaceDefaultsWithoutBeanNameOverrides() throws Exception {
        var identity = new AtomicReference<Authentication>(UsernamePasswordAuthenticationToken.authenticated(
                new ConsumerIdentity("external-account"), null, List.of(new SimpleGrantedAuthority("CONSUMER_VERIFIED"))));
        var denials = new AtomicInteger();
        PaperAuthenticationResolver resolver = input -> identity.get();
        PaperAccessDeniedHandler denied = (input, failure) -> { denials.incrementAndGet(); return false; };
        var previous = SecurityContextHolder.getContext();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setAllowBeanDefinitionOverriding(false);
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.registerBean("consumerIdentityResolver", PaperAuthenticationResolver.class, () -> resolver);
            context.registerBean("consumerDenialHandler", PaperAccessDeniedHandler.class, () -> denied);
            context.registerBean(ProtectedConsumerService.class);
            context.register(SecurityConfiguration.class);
            context.refresh();
            assertSame(resolver, context.getBean(PaperAuthenticationResolver.class));
            assertSame(denied, context.getBean(PaperAccessDeniedHandler.class));
            assertEquals(1, context.getBeansOfType(PaperAuthenticationResolver.class).size());
            assertEquals(1, context.getBeansOfType(PaperAccessDeniedHandler.class).size());
            var interceptor = context.getBean(PaperSecurityInterceptor.class);
            var protectedService = context.getBean(ProtectedConsumerService.class);
            Player player = mock(Player.class);
            var input = new PaperInvocation(player, "consumer-event", true, PaperInvocation.Kind.EVENT);
            assertEquals("consumer-allowed", interceptor.invoke(input, protectedService::execute));
            assertSame(previous, SecurityContextHolder.getContext());
            identity.set(null);
            assertEquals(false, interceptor.invoke(input, protectedService::execute));
            assertEquals(1, denials.get());
            verifyNoInteractions(player);
            assertSame(previous, SecurityContextHolder.getContext());
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void resolverFailureStillRestoresThePreviousInvocationContext() {
        var previous = SecurityContextHolder.getContext();
        var interceptor = new PaperSecurityInterceptor(input -> { throw new IllegalStateException("consumer resolver failed"); },
                (input, failure) -> true);
        try {
            assertThrows(IllegalStateException.class, () -> interceptor.invoke(
                    new PaperInvocation(null, "test", false, PaperInvocation.Kind.EVENT), () -> fail("Action must not run")));
            assertSame(previous, SecurityContextHolder.getContext());
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void resolverCannotAccidentallyInheritTheOuterCallersAuthentication() throws Exception {
        var previous = SecurityContextHolder.createEmptyContext();
        previous.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("outer-admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(previous);
        var interceptor = new PaperSecurityInterceptor(input -> {
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }, (input, failure) -> false);
        try {
            assertNull(interceptor.invoke(new PaperInvocation(null, "nested", false, PaperInvocation.Kind.EVENT),
                    () -> SecurityContextHolder.getContext().getAuthentication()));
            assertSame(previous, SecurityContextHolder.getContext());
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void consumerResolverAuthenticationFailureUsesTheConfiguredDenialHandler() throws Exception {
        var interceptor = new PaperSecurityInterceptor(input -> {
            throw new org.springframework.security.authentication.CredentialsExpiredException("expired consumer session");
        }, (input, failure) -> false);
        try {
            assertEquals(false, interceptor.invoke(new PaperInvocation(null, "test", false, PaperInvocation.Kind.COMMAND),
                    () -> fail("An expired identity must not invoke the action")));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
