package org.blu3fishez.springpaper.examples.jdbc.service;

import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.examples.jdbc.repository.AccountRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class JdbcVerificationService {
    private final AccountRepository accounts;
    private final TransferService transfers;
    public String verify() {
        accounts.reset();
        // Cross the Spring proxy through the injected service; self-invocation would bypass the transaction.
        transfers.transfer(25, false);
        requireBalances(75, 75);
        try { transfers.transfer(11, true); throw new IllegalStateException("Failure was not propagated"); }
        catch (IllegalArgumentException expected) { requireBalances(75, 75); }
        return "JDBC_OK committed=75/75 rollback=75/75 jpa=absent";
    }
    private void requireBalances(int source, int target) {
        if (accounts.balance("source") != source || accounts.balance("target") != target) {
            throw new IllegalStateException("Atomic transfer verification failed");
        }
    }
}
