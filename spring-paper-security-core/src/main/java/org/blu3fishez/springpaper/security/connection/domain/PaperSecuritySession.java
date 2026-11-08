package org.blu3fishez.springpaper.security.connection.domain;

import java.time.Instant;
import java.util.Objects;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

/** Repository snapshot. Authentication/principal objects must not be mutated after publication. */
public record PaperSecuritySession(PaperSecurityConnection connection, long revision, Status status,
                                   Authentication authentication, PaperAuthenticationChallenge challenge,
                                   Instant expiresAt, String reason) {
    public enum Status { PENDING, AUTHENTICATED, DENIED, EXPIRED, CLOSED }
    public PaperSecuritySession {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(reason, "reason");
        if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
        if (authentication != null && (!authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)) {
            throw new IllegalArgumentException("Session authentication must be verified and non-anonymous");
        }
        if (status == Status.AUTHENTICATED && authentication == null) throw new IllegalArgumentException("Authentication is required");
        if (status != Status.PENDING && challenge != null) throw new IllegalArgumentException("Only pending sessions have challenges");
        if (status != Status.PENDING && status != Status.AUTHENTICATED && authentication != null) {
            throw new IllegalArgumentException("Terminal sessions cannot retain authentication");
        }
    }
    public static PaperSecuritySession pending(PaperSecurityConnection connection, Instant deadline) {
        return new PaperSecuritySession(connection, 0, Status.PENDING, null, null, deadline, "authentication_required");
    }
    public boolean active(Instant now) {
        return (status == Status.PENDING || status == Status.AUTHENTICATED) && now.isBefore(expiresAt);
    }
    public boolean authorized(Instant now) { return status == Status.AUTHENTICATED && active(now); }
    public PaperSecuritySession finish(Status terminal, Instant now) {
        if (terminal == Status.PENDING || terminal == Status.AUTHENTICATED) throw new IllegalArgumentException("Terminal status is required");
        return new PaperSecuritySession(connection, Math.incrementExact(revision), terminal, null, null, now,
                terminal.name().toLowerCase(java.util.Locale.ROOT));
    }
    @Override public String toString() {
        return "PaperSecuritySession[connection=" + connection.connectionId() + ", revision=" + revision + ", status=" + status + "]";
    }
}
