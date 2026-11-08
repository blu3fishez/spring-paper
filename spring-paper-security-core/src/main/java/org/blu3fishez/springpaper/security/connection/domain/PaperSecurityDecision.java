package org.blu3fishez.springpaper.security.connection.domain;

import java.util.Objects;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

/** A filter outcome. Pending may retain verified partial authentication, but cannot authorize gameplay. */
public record PaperSecurityDecision(Status status, Authentication authentication,
                                    PaperAuthenticationChallenge challenge, String reason) {
    public enum Status { AUTHENTICATED, PENDING, DENIED }
    public PaperSecurityDecision {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        if (authentication != null && (!authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)) {
            throw new IllegalArgumentException("Only verified non-anonymous authentication may be retained");
        }
        if (status == Status.AUTHENTICATED && authentication == null) throw new IllegalArgumentException("Authentication is required");
        if (status == Status.PENDING && challenge == null) throw new IllegalArgumentException("Challenge is required");
        if (status != Status.PENDING && challenge != null) throw new IllegalArgumentException("Only pending decisions have challenges");
        if (status == Status.DENIED && authentication != null) throw new IllegalArgumentException("Denied decisions cannot retain authentication");
    }
    public static PaperSecurityDecision authenticated(Authentication authentication) {
        return new PaperSecurityDecision(Status.AUTHENTICATED, authentication, null, "authenticated");
    }
    public static PaperSecurityDecision pending(PaperAuthenticationChallenge challenge, Authentication partial) {
        return new PaperSecurityDecision(Status.PENDING, partial, challenge, "authentication_required");
    }
    public static PaperSecurityDecision denied(String reason) {
        return new PaperSecurityDecision(Status.DENIED, null, null, reason);
    }
    @Override public String toString() { return "PaperSecurityDecision[status=" + status + ", reason=" + reason + "]"; }
}
