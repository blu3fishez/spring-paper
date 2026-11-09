package org.blu3fishez.springpaper.jpa;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.jpa.fixture.NoteRepository;
import org.blu3fishez.springpaper.jpa.fixture.StoredNote;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ExistingJpaInfrastructureTest {
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = NoteRepository.class)
    public static class ApplicationPersistence {
        @Bean LocalContainerEntityManagerFactoryBean applicationEntities() {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            var entities = new LocalContainerEntityManagerFactoryBean();
            entities.setDataSource(source);
            entities.setPackagesToScan(StoredNote.class.getPackageName());
            entities.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            entities.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return entities;
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class ApplicationTransactions {
        @Bean PlatformTransactionManager applicationTransactions(EntityManagerFactory entities) {
            return new JpaTransactionManager(entities);
        }
    }

    @Test void existingFactoryWorksWithoutPublicDataSourceOrPaperPersistenceSettings() {
        EntityManagerFactory supplied;
        try (var context = context(false, false)) {
            supplied = context.getBean("applicationEntities", EntityManagerFactory.class);
            assertSame(supplied, context.getBean("entityManagerFactory"));
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            assertFalse(context.containsBeanDefinition("springPaperDataSource"));
            assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
            verifyCommitAndRollback(context);
        }
        assertFalse(supplied.isOpen());
    }

    @Test void existingTransactionManagerIsReusedWithItsApplicationOwnedFactory() {
        try (var context = context(true, false)) {
            assertSame(context.getBean("applicationTransactions"), context.getBean("transactionManager"));
            assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            verifyCommitAndRollback(context);
        }
    }

    @Test void unrelatedDataSourcesDoNotRequireSelectionWhenTheFactoryAlreadyExists() {
        try (var context = context(true, true)) {
            assertEquals(2, context.getBeansOfType(DataSource.class).size());
            assertFalse(context.containsBeanDefinition("springPaperDataSource"));
            verifyCommitAndRollback(context);
        }
    }

    private AnnotationConfigApplicationContext context(boolean suppliedManager, boolean unrelatedSources) {
        var context = new AnnotationConfigApplicationContext();
        context.setAllowBeanDefinitionOverriding(false);
        context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
        context.registerBean(PaperThreadAccess.class, () -> () -> false);
        context.register(ApplicationPersistence.class, JpaConfiguration.class);
        if (suppliedManager) context.register(ApplicationTransactions.class);
        if (unrelatedSources) {
            // These sources have no URL. Repository operations must use the factory's private source.
            context.registerBean("unrelatedFirst", DataSource.class, JdbcDataSource::new);
            context.registerBean("unrelatedSecond", DataSource.class, JdbcDataSource::new);
        }
        try { context.refresh(); return context; }
        catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }

    private void verifyCommitAndRollback(AnnotationConfigApplicationContext context) {
        var notes = context.getBean(NoteRepository.class);
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> notes.save(new StoredNote(1, "application-owned factory")));
        assertEquals("application-owned factory", notes.findById(1L).orElseThrow().text());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            notes.save(new StoredNote(2, "rollback"));
            throw new IllegalStateException("deliberate rollback");
        }));
        assertFalse(notes.existsById(2L));
    }
}
