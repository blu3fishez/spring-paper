package org.blu3fishez.springpaper.security.connection.domain;

import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.Authentication;

/** Credentials are input to a provider, never evidence of successful authentication on their own. */
public record PaperSecurityRequest(PaperSecurityConnection connection, Stage stage,
                                   Authentication credentials, Map<String, String> attributes) {
    public enum Stage { CONNECT, AUTHENTICATE, REVALIDATE }
    public PaperSecurityRequest {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(stage, "stage");
        attributes = Map.copyOf(attributes);
    }
    public PaperSecurityRequest(PaperSecurityConnection connection, Stage stage, Authentication credentials) {
        this(connection, stage, credentials, Map.of());
    }
    @Override public String toString() {
        return "PaperSecurityRequest[connection=" + connection.connectionId() + ", stage=" + stage + "]";
    }
}
