package org.blu3fishez.springpaper.examples.approval.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.examples.approval.domain.OnlineIdentityToken;
import org.blu3fishez.springpaper.examples.approval.repository.ApprovalRepository;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class ApprovalVerificationService {
    private final PaperConnectionSecurityService security;
    private final ApprovalRepository approvals;
    /** Console-only synthetic transport check. This does not claim to test a real Mojang login. */
    public CompletionStage<String> verify() {
        var connection = PaperSecurityConnection.create(UUID.randomUUID(), "ApprovalVerificationFixture");
        var proof = new OnlineIdentityToken(connection.playerId(), connection.name());
        CompletionStage<String> flow = security.open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, proof))
                .thenCompose(pending -> {
                    if (pending.status() != PaperSecuritySession.Status.PENDING) throw new IllegalStateException("Approval was bypassed");
                    approvals.approve(connection.playerId());
                    return security.authenticate(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.AUTHENTICATE, proof));
                }).thenApply(accepted -> {
                    if (accepted.status() != PaperSecuritySession.Status.AUTHENTICATED) throw new IllegalStateException("Approval failed");
                    return "APPROVAL_OK pending=observed authenticated=provider disconnect=clean";
                });
        return flow.handle((result, failure) -> security.disconnect(connection).thenCompose(ignored -> {
            approvals.remove(connection.playerId());
            if (failure != null) return CompletableFuture.<String>failedFuture(failure);
            return security.session(connection).thenApply(found -> {
                if (found.isPresent()) throw new IllegalStateException("Disconnected session remained");
                return result;
            });
        })).thenCompose(stage -> stage);
    }
}
