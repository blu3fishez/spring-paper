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

class JpaIntegrationTest {
    private AnnotationConfigApplicationContext context(boolean supplied, boolean mainThread) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.paper.jpa.repositories", "org.blu3fishez.springpaper.jpa.fixture",
                "spring.paper.jpa.entities", "org.blu3fishez.springpaper.jpa.fixture",
                "spring.paper.jpa.datasource.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "spring.paper.jpa.properties.hibernate.hbm2ddl.auto", "create-drop")));
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
}
