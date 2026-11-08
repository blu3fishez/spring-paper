package org.blu3fishez.springpaper.security.spi;

import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.springframework.security.access.AccessDeniedException;

/** Consumer-owned denial response. Event denial alone does not cancel native Minecraft behavior. */
@FunctionalInterface
public interface PaperAccessDeniedHandler {
    Object handle(PaperInvocation invocation, AccessDeniedException denied) throws Exception;
}
