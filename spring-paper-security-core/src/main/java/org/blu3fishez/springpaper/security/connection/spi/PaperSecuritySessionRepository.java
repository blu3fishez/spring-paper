package org.blu3fishez.springpaper.security.connection.spi;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;

/**
 * Implemented by storage adapters; called by Spring Paper Security on any calling/completing thread.
 * Return the stage promptly and run DB/network I/O on the adapter's worker, never on the calling Paper thread.
 * All mutations are atomic for the complete connection identity. CAS compares revision as well as identity.
 * CAS must reject an expired expected session at commit time (for Redis, retain/check the server-side TTL).
 * Repositories own serialization; do not persist credentials, mutable SecurityContext objects or live players.
 */
public interface PaperSecuritySessionRepository {
    CompletionStage<Boolean> create(PaperSecuritySession initial);
    CompletionStage<Optional<PaperSecuritySession>> find(PaperSecurityConnection connection);
    CompletionStage<Boolean> compareAndSet(PaperSecuritySession expected, PaperSecuritySession replacement);
    CompletionStage<Boolean> remove(PaperSecuritySession expected);
    /**
     * Explicitly invalidate any revision of this exact connection, preserving other connections for the player.
     * Consumers may call this SPI for a deliberate remote revocation. Service disconnects only clean up
     * their own positively created rows; an unknown/failed local connection does not grant ownership here.
     */
    CompletionStage<Optional<PaperSecuritySession>> invalidate(PaperSecurityConnection connection);
}
