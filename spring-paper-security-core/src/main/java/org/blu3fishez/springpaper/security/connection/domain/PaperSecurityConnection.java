package org.blu3fishez.springpaper.security.connection.domain;

import java.util.Objects;
import java.util.UUID;

/** A transport adapter's immutable connection identity. Never reuse an id after disconnect. */
public record PaperSecurityConnection(UUID playerId, UUID connectionId, String name) {
    public PaperSecurityConnection {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (Objects.requireNonNull(name, "name").isBlank()) throw new IllegalArgumentException("name must not be blank");
    }
    public static PaperSecurityConnection create(UUID playerId, String name) {
        return new PaperSecurityConnection(playerId, UUID.randomUUID(), name);
    }
}
