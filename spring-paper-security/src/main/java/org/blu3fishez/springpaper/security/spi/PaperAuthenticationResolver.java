package org.blu3fishez.springpaper.security.spi;

import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.springframework.security.core.Authentication;

/** Consumer-supplied nonblocking invocation identity resolver. Null means unauthenticated; never perform I/O here. */
@FunctionalInterface
public interface PaperAuthenticationResolver {
    Authentication resolve(PaperInvocation invocation);
}
