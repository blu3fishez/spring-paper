package org.blu3fishez.springpaper.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

class JdbcIntegrationTest {
    public record Player(UUID playerId, String displayName) {}
    public static class Accounts {
        private final JdbcClient jdbc;
        private final NamedParameterJdbcOperations batch;
        public Accounts(JdbcClient jdbc, NamedParameterJdbcOperations batch) { this.jdbc = jdbc; this.batch = batch; }
        @Transactional public void create(UUID id, boolean fail) {
            jdbc.sql("INSERT INTO players VALUES (:id, :name)").param("id", id.toString()).param("name", "first").update();
            if (fail) throw new IllegalStateException("rollback");
            batch.batchUpdate("UPDATE players SET display_name=:name WHERE player_id=:id", new MapSqlParameterSource[] {
                    new MapSqlParameterSource("id", id.toString()).addValue("name", "committed")});
        }
    }
    private static final class CountedSource extends DelegatingDataSource {
        final AtomicInteger opened = new AtomicInteger();
        CountedSource(DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException { opened.incrementAndGet(); return super.getConnection(); }
    }
    private static final class Fixture implements AutoCloseable {
        final JdbcDataSource database = new JdbcDataSource();
        final CountedSource source = new CountedSource(database);
        final AtomicBoolean primary = new AtomicBoolean();
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Fixture(JdbcTemplate existing, JdbcClient client) {
            database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(PlatformTransactionManager.class, () -> PaperJdbcTransactions.workerOnly(source, primary::get));
            if (existing != null) { existing.setDataSource(source); context.registerBean("customJdbc", JdbcTemplate.class, () -> existing); }
            if (client != null) context.registerBean("customClient", JdbcClient.class, () -> client);
            context.register(JdbcConfiguration.class, Accounts.class);
            context.refresh();
            context.getBean(JdbcOperations.class).execute("CREATE TABLE players(player_id VARCHAR(36) PRIMARY KEY, display_name VARCHAR(32))");
        }
        JdbcClient jdbc() { return context.getBean(JdbcClient.class); }
        @Override public void close() {
            context.close();
            try (var connection = database.getConnection(); var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
    }

    @Test void jdbcClientAndBatchParticipateInOneStandardSpringTransactionWithoutJpa() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("jakarta.persistence.EntityManager"));
        try (var fixture = new Fixture(null, null)) {
            UUID committed = UUID.randomUUID(); UUID refused = UUID.randomUUID();
            var accounts = fixture.context.getBean(Accounts.class);
            accounts.create(committed, false);
            assertThrows(IllegalStateException.class, () -> accounts.create(refused, true));
            assertEquals(java.util.List.of(new Player(committed, "committed")),
                    fixture.jdbc().sql("SELECT * FROM players").query(Player.class).list());
        }
    }

    @Test void rejectsPrimaryThreadTransactionsBeforeAcquiringAConnection() {
        try (var fixture = new Fixture(null, null)) {
            fixture.primary.set(true);
            int before = fixture.source.opened.get();
            assertThrows(IllegalStateException.class,
                    () -> fixture.context.getBean(Accounts.class).create(UUID.randomUUID(), false));
            assertEquals(before, fixture.source.opened.get());
            fixture.primary.set(false);
            assertEquals(0, fixture.jdbc().sql("SELECT COUNT(*) FROM players").query(Integer.class).single());
        }
    }

    @Test void consumerJdbcTemplateIsReusedForBothFacades() {
        var template = new JdbcTemplate(); template.setQueryTimeout(13);
        try (var fixture = new Fixture(template, null)) {
            assertSame(template, fixture.context.getBean(JdbcOperations.class));
            assertEquals(1, fixture.context.getBeansOfType(JdbcOperations.class).size());
            assertEquals(13, template.getQueryTimeout());
            fixture.context.getBean(Accounts.class).create(UUID.randomUUID(), false);
            assertEquals(1, fixture.jdbc().sql("SELECT COUNT(*) FROM players").query(Integer.class).single());
        }
    }

    @Test void consumerClientAndTransactionManagerAreNotReplaced() {
        var separate = new JdbcDataSource(); separate.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        var client = JdbcClient.create(separate);
        try (var fixture = new Fixture(null, client)) {
            assertSame(client, fixture.context.getBean(JdbcClient.class));
            assertEquals(1, fixture.context.getBeansOfType(JdbcClient.class).size());
            assertEquals(1, fixture.context.getBeansOfType(PlatformTransactionManager.class).size());
        }
    }
}
