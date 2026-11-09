package org.blu3fishez.springpaper.jpa;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.jpa.fixture.*;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

class JpaIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = NoteRepository.class)
    public static class StandardRepositories {}

    private AnnotationConfigApplicationContext context(boolean supplied, boolean mainThread, boolean standardScan) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.paper.jpa.repositories", "org.blu3fishez.springpaper.jpa.fixture",
                "spring.paper.jpa.entities", "org.blu3fishez.springpaper.jpa.fixture",
                "spring.paper.jpa.datasource.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "spring.paper.jpa.properties.hibernate.hbm2ddl.auto", "create-drop")));
        if (standardScan) {
            context.getEnvironment().getPropertySources().replace("test", new MapPropertySource("test", Map.of(
                    "spring.paper.jpa.entities", "org.blu3fishez.springpaper.jpa.fixture",
                    "spring.paper.jpa.datasource.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                    "spring.paper.jpa.properties.hibernate.hbm2ddl.auto", "create-drop")));
            context.register(StandardRepositories.class);
        }
        context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
        context.registerBean(PaperThreadAccess.class, () -> () -> mainThread);
        if (supplied) context.registerBean("providedDataSource", DataSource.class, () -> {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            return source;
        });
        context.register(JpaConfiguration.class);
        context.refresh();
        return context;
    }
    private AnnotationConfigApplicationContext context(boolean supplied, boolean mainThread) {
        return context(supplied, mainThread, false);
    }
    @Test void realRepositoryCommitsAndRollsBackWithOneDataSource() {
        try (var context = context(false, false)) {
            NoteRepository notes = context.getBean(NoteRepository.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> notes.save(new StoredNote(1, "committed")));
            assertEquals("committed", notes.findById(1L).orElseThrow().text());
            assertNotNull(notes.findById(1L).orElseThrow().revision());
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                notes.save(new StoredNote(2, "must roll back"));
                throw new IllegalStateException("rollback");
            }));
            assertFalse(notes.existsById(2L));
            assertEquals(1, context.getBeansOfType(DataSource.class).size());
        }
    }
    @Test void reusesAnExistingDataSource() {
        try (var context = context(true, false)) {
            assertSame(context.getBean("providedDataSource"), context.getBean(DataSource.class));
            assertFalse(context.containsBean("springPaperDataSource"));
            context.getBean(NoteRepository.class).save(new StoredNote(3, "provided"));
            assertTrue(context.getBean(NoteRepository.class).existsById(3L));
        }
    }
    @Test void rejectsTransactionsOnThePaperThreadBeforeAnyWrites() {
        try (var context = context(false, true)) {
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> fail("must not begin")));
        }
    }

    @Test void standardRepositoryScanWorksWithoutPaperRepositoryProperties() {
        try (var context = assertDoesNotThrow(() -> context(false, false, true))) {
            var notes = context.getBean(NoteRepository.class);
            notes.save(new StoredNote(7, "standard configuration"));
            assertEquals("standard configuration", notes.findById(7L).orElseThrow().text());
        }
    }

    @Test void specializedJdbcBatchAndJpaWritesShareOneTransactionAndRollbackTogether() {
        try (var context = context(false, false)) {
            var notes = context.getBean(NoteRepository.class);
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            var entities = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            jdbc.execute("CREATE TABLE batch_evidence(id BIGINT PRIMARY KEY)");
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                notes.save(new StoredNote(10, "committed"));
                entities.flush();
                jdbc.batchUpdate("INSERT INTO batch_evidence VALUES (?)", java.util.List.<Object[]>of(new Object[] {10L}));
            });
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                notes.save(new StoredNote(11, "rollback"));
                entities.flush();
                jdbc.batchUpdate("INSERT INTO batch_evidence VALUES (?)", java.util.List.<Object[]>of(new Object[] {11L}));
                throw new IllegalStateException("rollback both");
            }));
            assertTrue(notes.existsById(10L));
            assertFalse(notes.existsById(11L));
            assertEquals(java.util.List.of(10L), jdbc.queryForList("SELECT id FROM batch_evidence", Long.class));
            assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
            assertEquals(1, context.getBeansOfType(DataSource.class).size());
        }
    }
}
