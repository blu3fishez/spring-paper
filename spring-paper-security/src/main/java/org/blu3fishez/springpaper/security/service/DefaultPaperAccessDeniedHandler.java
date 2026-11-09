package org.blu3fishez.springpaper.security.service;

import lombok.RequiredArgsConstructor;

import java.util.List;
import org.blu3fishez.springpaper.security.spi.PaperAccessDeniedHandler;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.springframework.security.access.AccessDeniedException;

@RequiredArgsConstructor
public final class DefaultPaperAccessDeniedHandler implements PaperAccessDeniedHandler {
    private final String message;
    @Override public Object handle(PaperInvocation invocation, AccessDeniedException denied) {
        if (invocation.kind() == PaperInvocation.Kind.EVENT) throw denied;
        if (invocation.kind() == PaperInvocation.Kind.COMPLETION) return List.of();
        if (invocation.caller() != null) invocation.caller().sendPlainMessage(message);
        return true;
    }
}
