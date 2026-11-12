package org.blu3fishez.springpaper.jdbc.fixture.boot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public record JdbcQueries(JdbcClient client) {
    public int answer() { return client.sql("SELECT 42").query(Integer.class).single(); }
}
