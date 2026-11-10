package org.blu3fishez.springpaper.examples.business.inbox;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;

@RequiredArgsConstructor
public final class InboxRepository {
    private final JdbcClient jdbc;
    public int pending(UUID owner) {
        return jdbc.sql("SELECT COUNT(*) FROM inbox WHERE owner = :owner AND expires_at > CURRENT_TIMESTAMP")
                .param("owner", owner.toString()).query(Integer.class).single();
    }
}
