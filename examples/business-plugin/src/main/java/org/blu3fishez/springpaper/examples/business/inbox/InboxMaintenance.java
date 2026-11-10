package org.blu3fishez.springpaper.examples.business.inbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;

@RequiredArgsConstructor
public final class InboxMaintenance {
    private final JdbcOperations jdbc;
    @Scheduled(initialDelayString = "PT1S", fixedDelayString = "${example.inbox.cleanup-delay:PT1H}")
    public void deleteExpired() { jdbc.update("DELETE FROM inbox WHERE expires_at <= CURRENT_TIMESTAMP"); }
}
