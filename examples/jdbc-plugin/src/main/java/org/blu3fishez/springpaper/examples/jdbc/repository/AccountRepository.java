package org.blu3fishez.springpaper.examples.jdbc.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public final class AccountRepository {
    private final JdbcClient jdbc;
    public void reset() {
        jdbc.sql("UPDATE example_accounts SET balance = CASE id WHEN 'source' THEN 100 ELSE 50 END").update();
    }
    public void add(String id, int amount) {
        if (jdbc.sql("UPDATE example_accounts SET balance=balance+:amount WHERE id=:id")
                .param("amount", amount).param("id", id).update() != 1) throw new IllegalStateException("Account missing");
    }
    public int balance(String id) {
        return jdbc.sql("SELECT balance FROM example_accounts WHERE id=:id").param("id", id).query(Integer.class).single();
    }
}
