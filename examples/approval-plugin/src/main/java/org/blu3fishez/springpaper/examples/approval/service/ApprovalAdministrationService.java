package org.blu3fishez.springpaper.examples.approval.service;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.examples.approval.repository.ApprovalRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('CONSOLE')")
public class ApprovalAdministrationService {
    private final ApprovalRepository approvals;
    private final ApprovalVerificationService verification;
    public void approve(UUID playerId) { approvals.approve(playerId); }
    public CompletionStage<String> verify() { return verification.verify(); }
}
