package org.blu3fishez.springpaper.jpa;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.jpa.fixture.NoteRepository;
import org.blu3fishez.springpaper.jpa.fixture.StoredNote;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JpaResourceSelectionTest {
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = NoteRepository.class)
    static class DedicatedPersistence {
        @Bean(name = {"dedicatedEntities", "entityAlias"}, defaultCandidate = false)
        LocalContainerEntityManagerFactoryBean dedicatedEntities() {
            return newEntities();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = NoteRepository.class)
    static class RepositoryScan {}

    @Configuration(proxyBeanMethods = false)
    static class DedicatedTransactions {
        @Bean(name = {"dedicatedTransactions", "transactionAlias"}, defaultCandidate = false)
        PlatformTransactionManager dedicatedTransactions(@Qualifier("dedicatedEntities") EntityManagerFactory entities) {
            return new JpaTransactionManager(entities);
        }
    }

    public static class UninitializedEntities implements FactoryBean<EntityManagerFactory> {
        @Override public EntityManagerFactory getObject() { throw new AssertionError("Product must not be initialized during selection"); }
        @Override public Class<?> getObjectType() { return EntityManagerFactory.class; }
    }

    @SuppressWarnings("rawtypes")
    public static class LateTypedResource implements FactoryBean, DisposableBean {
        private final Object product;
        private final Runnable close;
        LateTypedResource(Object product, Runnable close) { this.product = product; this.close = close; }
        @Override public Object getObject() { return product; }
        @Override public Class<?> getObjectType() { return product.getClass(); }
        @Override public void destroy() { close.run(); }
    }

    @Test void explicitNamesSupportLateTypedFactoryBeanProductsWithoutGivingTheDefaultProviderOwnership() {
        var creations = new AtomicInteger();
        var closes = new AtomicInteger();
        EntityManagerFactory product;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setAllowBeanDefinitionOverriding(false);
            context.registerBean("entityManagerFactory", LateTypedResource.class, () -> {
                creations.incrementAndGet();
                var entities = newEntities();
                entities.afterPropertiesSet();
                return new LateTypedResource(entities.getObject(), () -> { closes.incrementAndGet(); entities.destroy(); });
            });
            context.registerBean("transactionManager", LateTypedResource.class, () -> new LateTypedResource(
                    new JpaTransactionManager(context.getBean("entityManagerFactory", EntityManagerFactory.class)), () -> {}));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("selected", Map.of(
                    "spring.paper.jpa.entity-manager-factory-bean", "entityManagerFactory",
                    "spring.paper.jpa.transaction-manager-bean", "transactionManager")));
            JpaConfiguration.springPaperJpaInfrastructure(new PaperConfiguration(context.getEnvironment()))
                    .postProcessBeanFactory(context.getBeanFactory());
            assertEquals(0, creations.get());
            assertFalse(context.containsBeanDefinition("springPaperDataSource"));
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.register(RepositoryScan.class, JpaConfiguration.class);
            context.refresh();
            product = context.getBean("entityManagerFactory", EntityManagerFactory.class);
            verifyCommitAndRollback(context);
            assertEquals(1, creations.get());
        }
        assertEquals(1, closes.get());
        assertFalse(product.isOpen());
    }

    @Test void selectingAnExternalSingletonDoesNotCloseItsFactoryOnContextShutdown() {
        var factory = newEntities();
        factory.afterPropertiesSet();
        var external = factory.getObject();
        try {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getBeanFactory().registerSingleton("externalEntities", external);
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("selected", Map.of(
                        "spring.paper.jpa.entity-manager-factory-bean", "externalEntities")));
                context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
                context.register(RepositoryScan.class, JpaConfiguration.class);
                context.refresh();
                assertSame(external, context.getBean("entityManagerFactory"));
                assertFalse(context.containsBeanDefinition("springPaperDataSource"));
                verifyCommitAndRollback(context);
            }
            assertTrue(external.isOpen());
        } finally { factory.destroy(); }
        assertFalse(external.isOpen());
    }

    @Test void explicitFactoryBeanSelectionUsesPredictedTypeWithoutInitializingTheFactoryOrItsProduct() {
        for (boolean conventionalAliasExists : new boolean[] {false, true}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                var creations = new AtomicInteger();
                context.setAllowBeanDefinitionOverriding(false);
                context.registerBean("dedicatedEntities", UninitializedEntities.class, () -> {
                    creations.incrementAndGet();
                    return new UninitializedEntities();
                }, definition -> ((org.springframework.beans.factory.support.AbstractBeanDefinition) definition).setDefaultCandidate(false));
                context.registerAlias("dedicatedEntities", "entityAlias");
                if (conventionalAliasExists) context.registerAlias("dedicatedEntities", "entityManagerFactory");
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("selected", Map.of(
                        "spring.paper.jpa.entity-manager-factory-bean", "entityAlias")));
                JpaConfiguration.springPaperJpaInfrastructure(new PaperConfiguration(context.getEnvironment()))
                        .postProcessBeanFactory(context.getBeanFactory());
                assertTrue(context.isAlias("entityManagerFactory"));
                assertEquals(0, creations.get());
                assertFalse(context.containsBeanDefinition("springPaperDataSource"));
                assertFalse(context.containsBeanDefinition("entityManagerFactory"));
            }
        }
    }

    @Test void explicitFactoryNamesAndAliasesSelectTheDedicatedProductWithoutCreatingAnUnusedPool() {
        for (String name : new String[] {"dedicatedEntities", "entityAlias"}) {
            EntityManagerFactory supplied;
            try (var context = context(Map.of("spring.paper.jpa.entity-manager-factory-bean", name), false)) {
                supplied = context.getBean("dedicatedEntities", EntityManagerFactory.class);
                assertSame(supplied, context.getBean("entityManagerFactory"));
                assertFalse(context.containsBeanDefinition("springPaperDataSource"));
                assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
                verifyCommitAndRollback(context);
            }
            assertFalse(supplied.isOpen());
        }
    }

    @Test void explicitTransactionManagerNamesAndAliasesKeepTheConsumerManager() {
        for (String name : new String[] {"dedicatedTransactions", "transactionAlias"}) {
            try (var context = context(Map.of(
                    "spring.paper.jpa.entity-manager-factory-bean", "dedicatedEntities",
                    "spring.paper.jpa.transaction-manager-bean", name), true)) {
                assertSame(context.getBean("dedicatedTransactions"), context.getBean("transactionManager"));
                assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
                verifyCommitAndRollback(context);
            }
        }
    }

    @Test void aConfiguredMissingOrWrongTypeResourceDoesNotSilentlyCreateADefault() {
        for (String key : new String[] {"spring.paper.jpa.entity-manager-factory-bean", "spring.paper.jpa.transaction-manager-bean",
                "spring.paper.jpa.data-source-bean"}) {
            for (String name : new String[] {"missingResource", "wrongType"}) {
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("selected", Map.of(key, name)));
                    context.registerBean("wrongType", String.class, () -> "not persistence infrastructure");
                    assertThrows(IllegalArgumentException.class, () -> JpaConfiguration.springPaperJpaInfrastructure(
                            new PaperConfiguration(context.getEnvironment())).postProcessBeanFactory(context.getBeanFactory()));
                    assertFalse(context.containsBeanDefinition("springPaperDataSource"));
                }
            }
        }
    }

    @Test void explicitConventionalNamesKeepTheirOriginalDedicatedDefinitions() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setAllowBeanDefinitionOverriding(false);
            var entities = new RootBeanDefinition(UninitializedEntities.class);
            entities.setDefaultCandidate(false);
            var transactions = new RootBeanDefinition(PlatformTransactionManager.class, () -> {
                throw new AssertionError("A private transaction manager must not be initialized during selection");
            });
            transactions.setDefaultCandidate(false);
            context.registerBeanDefinition("entityManagerFactory", entities);
            context.registerBeanDefinition("transactionManager", transactions);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("selected", Map.of(
                    "spring.paper.jpa.entity-manager-factory-bean", "entityManagerFactory",
                    "spring.paper.jpa.transaction-manager-bean", "transactionManager")));
            assertDoesNotThrow(() -> JpaConfiguration.springPaperJpaInfrastructure(new PaperConfiguration(context.getEnvironment()))
                    .postProcessBeanFactory(context.getBeanFactory()));
            assertSame(entities, context.getBeanFactory().getBeanDefinition("entityManagerFactory"));
            assertSame(transactions, context.getBeanFactory().getBeanDefinition("transactionManager"));
            assertFalse(context.containsBeanDefinition("springPaperDataSource"));
        }
    }

    @Test void privateConventionalNamesAreNeverReplacedOrSilentlyUsedAsGeneralInfrastructure() {
        for (boolean overrideAllowed : new boolean[] {false, true}) {
            for (String name : new String[] {"entityManagerFactory", "transactionManager"}) {
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.setAllowBeanDefinitionOverriding(overrideAllowed);
                    var creations = new AtomicInteger();
                    Class<?> type = name.equals("entityManagerFactory") ? EntityManagerFactory.class : PlatformTransactionManager.class;
                    var definition = new RootBeanDefinition(type, () -> {
                        creations.incrementAndGet();
                        throw new AssertionError("A private resource must not be initialized during default selection");
                    });
                    definition.setDefaultCandidate(false);
                    context.registerBeanDefinition(name, definition);
                    var failure = assertThrows(IllegalStateException.class, () -> JpaConfiguration.springPaperJpaInfrastructure(
                            new PaperConfiguration(context.getEnvironment())).postProcessBeanFactory(context.getBeanFactory()));
                    assertTrue(failure.getMessage().contains(name));
                    assertSame(definition, context.getBeanFactory().getBeanDefinition(name));
                    assertEquals(0, creations.get());
                    assertFalse(context.containsBeanDefinition("springPaperDataSource"));
                }
            }
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> properties, boolean suppliedTransactions) {
        var context = new AnnotationConfigApplicationContext();
        context.setAllowBeanDefinitionOverriding(false);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("selected", properties));
        context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
        context.register(DedicatedPersistence.class, JpaConfiguration.class);
        if (suppliedTransactions) context.register(DedicatedTransactions.class);
        try { context.refresh(); return context; }
        catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }

    private void verifyCommitAndRollback(AnnotationConfigApplicationContext context) {
        var notes = context.getBean(NoteRepository.class);
        var transaction = new TransactionTemplate(context.getBean("transactionManager", PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> notes.save(new StoredNote(1, "selected resource")));
        assertEquals("selected resource", notes.findById(1L).orElseThrow().text());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            notes.save(new StoredNote(2, "rollback"));
            throw new IllegalStateException("deliberate rollback");
        }));
        assertFalse(notes.existsById(2L));
    }

    private static LocalContainerEntityManagerFactoryBean newEntities() {
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
