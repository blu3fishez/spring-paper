package org.blu3fishez.springpaper.security.connection.domain;

import java.util.Map;
import java.util.Objects;

/** Consumer-defined challenge, such as an opaque browser challenge reference. Never include credentials. */
public record PaperAuthenticationChallenge(String type, Map<String, String> parameters) {
    public PaperAuthenticationChallenge {
        if (Objects.requireNonNull(type, "type").isBlank()) throw new IllegalArgumentException("challenge type is required");
        parameters = Map.copyOf(parameters);
    }
    @Override public String toString() { return "PaperAuthenticationChallenge[type=" + type + "]"; }
}
