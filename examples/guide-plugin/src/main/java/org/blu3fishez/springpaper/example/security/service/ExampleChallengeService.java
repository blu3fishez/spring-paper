package org.blu3fishez.springpaper.example.security.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.blu3fishez.springpaper.example.security.domain.ExampleProofToken;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.blu3fishez.springpaper.security.connection.spi.PaperAuthenticationEntryPoint;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler;

/** Consumer-owned waiting implementation. The library has no knowledge of this confirmation protocol. */
public final class ExampleChallengeService implements PaperAuthenticationEntryPoint, PaperSecurityResultHandler {
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    @Override public CompletionStage<Void> commence(PaperSecuritySession session) {
        pending.put(session.connection().connectionId(), new Pending(session.connection(), session.challenge().parameters().get("confirmation")));
        return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<Void> handle(PaperSecuritySession session, Throwable failure) {
        pending.remove(session.connection().connectionId());
        return CompletableFuture.completedFuture(null);
    }
    public String response(PaperSecurityConnection connection) { return pending.get(connection.connectionId()).response; }
    public boolean consume(ExampleProofToken proof) {
        Pending challenge = pending.get(proof.connection().connectionId());
        return challenge != null && challenge.connection.equals(proof.connection())
                && challenge.response.equals(proof.getCredentials()) && pending.remove(proof.connection().connectionId(), challenge);
    }
    private record Pending(PaperSecurityConnection connection, String response) {}
}
