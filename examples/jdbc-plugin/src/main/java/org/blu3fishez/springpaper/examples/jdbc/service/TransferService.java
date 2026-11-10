package org.blu3fishez.springpaper.examples.jdbc.service;

import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.examples.jdbc.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferService {
    private final AccountRepository accounts;
    @Transactional
    public void transfer(int amount, boolean failAfterDebit) {
        accounts.add("source", -amount);
        if (failAfterDebit) throw new IllegalArgumentException("Deliberate rollback example");
        accounts.add("target", amount);
    }
}
