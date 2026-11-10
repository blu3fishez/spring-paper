package org.blu3fishez.springpaper.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcDefaultsTest {
    record Consumer(JdbcOperations operations, NamedParameterJdbcOperations named, JdbcClient client) {}
    @Configuration(proxyBeanMethods = false)
    static class Database {
        @Bean DataSource database() {
            var database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:" + java.util.UUID.randomUUID());
            return database;
        }
        @Bean Consumer consumer(JdbcOperations operations, NamedParameterJdbcOperations named, JdbcClient client) {
            return new Consumer(operations, named, client);
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class Qualified {
        @Bean(defaultCandidate = false) JdbcOperations reportingOnly(DataSource source) { return new JdbcTemplate(source); }
    }
    @Configuration(proxyBeanMethods = false)
    static class Default {
        @Bean JdbcOperations applicationJdbc(DataSource source) { return new JdbcTemplate(source); }
    }
    @Configuration(proxyBeanMethods = false)
    static class Primary {
        @Bean @org.springframework.context.annotation.Primary
        JdbcOperations preferredJdbc(DataSource source) { return new JdbcTemplate(source); }
    }
    @Configuration(proxyBeanMethods = false)
    static class Fallback {
        @Bean @org.springframework.context.annotation.Fallback
        JdbcOperations fallbackJdbc(DataSource source) { return new JdbcTemplate(source); }
    }
    @Test void noConsumerDefaultUsesTypedLazyInfrastructureAndNoConnectionsUntilTheQuery() {
        try (var context = context(Database.class)) {
            assertEquals(17, context.getBean(Consumer.class).client().sql("SELECT 17").query(Integer.class).single());
            assertSame(context.getBean(Consumer.class).operations(), context.getBean("jdbcTemplate"));
        }
    }
    @Test void qualifierOnlyOperationsCannotSuppressTheDefaultForAnUnqualifiedConsumer() {
        try (var context = context(Database.class, Qualified.class)) {
            var consumer = context.getBean(Consumer.class);
            assertNotSame(context.getBean("reportingOnly"), consumer.operations());
            assertEquals(23, consumer.client().sql("SELECT 23").query(Integer.class).single());
        }
    }
    @Test void consumerDefaultIsReusedWithOrWithoutAQualifiedNeighbour() {
        for (boolean qualified : new boolean[] {false, true}) {
            try (var context = qualified ? context(Database.class, Default.class, Qualified.class) : context(Database.class, Default.class)) {
                assertSame(context.getBean("applicationJdbc"), context.getBean(Consumer.class).operations());
                assertEquals(qualified ? 2 : 1, context.getBeansOfType(JdbcOperations.class).size());
            }
        }
    }
    @Test void primaryAndFallbackUseSpringSelectionAndConsumerFallbackReplacesTheLibraryDefault() {
        try (var context = context(Database.class, Default.class, Primary.class, Qualified.class)) {
            assertSame(context.getBean("preferredJdbc"), context.getBean(Consumer.class).operations());
        }
        try (var context = context(Database.class, Fallback.class, Qualified.class)) {
            assertSame(context.getBean("fallbackJdbc"), context.getBean(Consumer.class).operations());
        }
        try (var context = context(Database.class, Default.class, Fallback.class)) {
            assertSame(context.getBean("applicationJdbc"), context.getBean(Consumer.class).operations());
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class ClientOnly {
        @Bean JdbcClient client() { return JdbcClient.create(new JdbcTemplate()); }
        @Bean String consumer(JdbcClient client) { return "no default provider needed"; }
    }
    @Test void suppliedClientDoesNotCreateAnUnusedTemplateOrRequireADataSource() {
        try (var context = context(ClientOnly.class)) {
            assertEquals("no default provider needed", context.getBean(String.class));
        }
    }
    public static class OperationsFactory implements FactoryBean<JdbcOperations> {
        final AtomicInteger creations = new AtomicInteger();
        final JdbcOperations operations = new JdbcTemplate();
        @Override public JdbcOperations getObject() { creations.incrementAndGet(); return operations; }
        @Override public Class<?> getObjectType() { return JdbcOperations.class; }
    }
    @Test void factoryBeanProductIsReusedWithoutRunningItDuringDefinitionProcessing() {
        var product = new OperationsFactory();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("factoryJdbc", OperationsFactory.class, () -> product);
            context.register(JdbcConfiguration.class, Database.class);
            context.addBeanFactoryPostProcessor(beans -> assertEquals(0, product.creations.get()));
            context.refresh();
            assertSame(product.operations, context.getBean(Consumer.class).operations());
            assertEquals(1, product.creations.get());
        }
    }
    @Test void realTypedDefaultsAreVisibleBeforeAnyBeanInstantiation() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        new AnnotatedBeanDefinitionReader(beans).register(JdbcConfiguration.class);
        new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(beans);
        assertArrayEquals(new String[] {"paperJdbcTemplate"}, beans.getBeanNamesForType(JdbcOperations.class, true, false));
        for (String name : new String[] {"paperJdbcTemplate", "paperNamedJdbcTemplate", "paperJdbcClient"}) {
            assertFalse(beans.containsSingleton(name));
            var definition = (org.springframework.beans.factory.support.AbstractBeanDefinition) beans.getBeanDefinition(name);
            assertTrue(definition.isLazyInit());
            assertTrue(definition.isFallback());
        }
    }
    private AnnotationConfigApplicationContext context(Class<?>... configurations) {
        var context = new AnnotationConfigApplicationContext();
        context.register(JdbcConfiguration.class);
        context.register(configurations);
        try { context.refresh(); return context; }
        catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }
}
