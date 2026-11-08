package org.blu3fishez.springpaper.example.security.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.example.security.domain.ExampleProofToken;
import org.blu3fishez.springpaper.example.security.domain.ExampleSecurityPrincipal;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.springframework.stereotype.Service;

@Service
public final class ExampleConnectionVerificationService {
    private final PaperConnectionSecurityService security;
    private final ExampleChallengeService challenges;
    public ExampleConnectionVerificationService(PaperConnectionSecurityService security, ExampleChallengeService challenges) {
        this.security = security; this.challenges = challenges;
    }
    public CompletionStage<String> verify() {
        var connection = PaperSecurityConnection.create(UUID.randomUUID(), "VerificationFixture");
        CompletionStage<String> flow = security.open(request(connection, false, null)).thenCompose(first -> {
            if (first.status() != PaperSecuritySession.Status.PENDING) throw new IllegalStateException("Consumer pending flow was not invoked");
            return security.authenticate(request(connection, true, challenges.response(connection)));
        }).thenApply(authenticated -> {
            if (authenticated.status() != PaperSecuritySession.Status.AUTHENTICATED
                    || !(authenticated.authentication().getPrincipal() instanceof ExampleSecurityPrincipal principal)
                    || !principal.playerId().equals(connection.playerId())) throw new IllegalStateException("Consumer authentication failed");
            return "connection-security=custom-pending-authenticated-closed";
        });
        return flow.handle((result, failure) -> security.disconnect(connection).thenCompose(ignored -> {
            if (failure != null) return CompletableFuture.<String>failedFuture(failure);
            return security.session(connection).thenApply(found -> {
                if (found.isPresent()) throw new IllegalStateException("Consumer security session survived disconnect");
                return result;
            });
        })).thenCompose(stage -> stage);
    }
    private PaperSecurityRequest request(PaperSecurityConnection connection, boolean confirmation, String response) {
        return new PaperSecurityRequest(connection, confirmation ? PaperSecurityRequest.Stage.AUTHENTICATE : PaperSecurityRequest.Stage.CONNECT,
                new ExampleProofToken(connection, confirmation, response), Map.of("verification-fixture", "true"));
    }
}
