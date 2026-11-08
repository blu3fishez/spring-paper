package org.blu3fishez.springpaper.security.connection.domain;

import java.util.Objects;
import org.springframework.security.core.Authentication;

/** Authentication travels as a value across async stages; a ThreadLocal is not an async session store. */
public record PaperSecurityExchange(PaperSecurityRequest request, PaperSecuritySession session,
                                    Authentication authentication) {
    public PaperSecurityExchange {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(session, "session");
        if (!request.connection().equals(session.connection())) throw new IllegalArgumentException("Connection mismatch");
    }
    public PaperSecurityExchange withAuthentication(Authentication value) {
        return new PaperSecurityExchange(request, session, value);
    }
}
