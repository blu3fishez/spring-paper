package org.blu3fishez.springpaper.security;

import org.blu3fishez.springpaper.security.service.DefaultPaperAccessDeniedHandler;
import org.blu3fishez.springpaper.security.service.PaperPlatformAuthenticationResolver;
import org.blu3fishez.springpaper.security.spi.PaperAuthenticationResolver;
import org.blu3fishez.springpaper.security.spi.PaperAccessDeniedHandler;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.blu3fishez.springpaper.spi.PaperInvocationInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Invocation scope only; identity and denial policy are consumer-replaceable beans. */
public final class PaperSecurityInterceptor implements PaperInvocationInterceptor {
    private final PaperAuthenticationResolver authentication;
    private final PaperAccessDeniedHandler denied;
    public PaperSecurityInterceptor(String deniedMessage) {
        this(new PaperPlatformAuthenticationResolver(), new DefaultPaperAccessDeniedHandler(deniedMessage));
    }
    public PaperSecurityInterceptor(PaperAuthenticationResolver authentication, PaperAccessDeniedHandler denied) {
        this.authentication = java.util.Objects.requireNonNull(authentication);
        this.denied = java.util.Objects.requireNonNull(denied);
    }

    @Override public Object invoke(PaperInvocation invocation, Invocation next) throws Exception {
        var strategy = SecurityContextHolder.getContextHolderStrategy();
        var previous = strategy.getContext();
        var context = strategy.createEmptyContext();
        try {
            strategy.setContext(context);
            // 기존 구현은 Bukkit 권한을 읽는 동시에 authenticated를 고정 생성했다.
            // 인증 완료 여부는 소비자 resolver의 결과로 분리하고, 이 클래스는 문맥 수명만 소유한다.
            context.setAuthentication(authentication.resolve(invocation));
            return next.call();
        } catch (AccessDeniedException failure) {
            return denied.handle(invocation, failure);
        } catch (AuthenticationException failure) {
            return denied.handle(invocation, new AccessDeniedException("Authentication is required", failure));
        } finally { strategy.setContext(previous); }
    }
}
