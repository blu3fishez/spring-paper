package org.blu3fishez.springpaper.jpa;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

class JpaInheritedResourceSelectionTest {
    private record Resource(String key, String reference, Class<?> type, Supplier<Object> product) {}
    private static final List<Resource> RESOURCES = List.of(
            new Resource("spring.paper.jpa.entity-manager-factory-bean", "entityManagerFactory", EntityManagerFactory.class,
                    () -> identity(EntityManagerFactory.class)),
            new Resource("spring.paper.jpa.transaction-manager-bean", "transactionManager", PlatformTransactionManager.class,
                    () -> identity(PlatformTransactionManager.class)),
            new Resource("spring.paper.jpa.data-source-bean", null, DataSource.class, JdbcDataSource::new));

    @SuppressWarnings("rawtypes")
    public static class UnknownFactory implements FactoryBean {
        private final Object product;
        UnknownFactory(Object product) { this.product = product; }
        @Override public Object getObject() { return product; }
        @Override public Class<?> getObjectType() { return product.getClass(); }
    }
    public static class WrongFactory implements FactoryBean<String> {
        @Override public String getObject() { throw new AssertionError("Wrong typed product must not be created"); }
        @Override public Class<?> getObjectType() { return String.class; }
    }

    @Test void inheritedUnknownFactoryNamesAndParentOrChildAliasesAreSelectedWithoutEagerInitialization() {
        for (var resource : RESOURCES) {
            for (String selected : List.of("parentResource", "parentAlias", "childAlias")) {
                var parent = new DefaultListableBeanFactory();
                var child = new DefaultListableBeanFactory(parent);
                var creations = new AtomicInteger();
                Object product = resource.product.get();
                register(parent, child, resource, new RootBeanDefinition(UnknownFactory.class, () -> {
                    creations.incrementAndGet(); return new UnknownFactory(product);
                }));
                select(child, resource.key, selected);
                assertEquals(0, creations.get(), resource.key + ": " + selected);
                assertFalse(child.containsBeanDefinition("springPaperDataSource"));

                String reference = resource.reference == null ? selected : resource.reference;
                assertSame(product, child.getBean(reference, resource.type));
                assertEquals(1, creations.get());
            }
        }
    }

    @Test void inheritedPredictableWrongFactoryTypesAreRejectedWithoutCreatingTheFactoryOrDefaults() {
        for (var resource : RESOURCES) {
            for (String selected : List.of("parentResource", "parentAlias", "childAlias")) {
                var parent = new DefaultListableBeanFactory();
                var child = new DefaultListableBeanFactory(parent);
                var creations = new AtomicInteger();
                register(parent, child, resource, new RootBeanDefinition(WrongFactory.class, () -> {
                    creations.incrementAndGet(); return new WrongFactory();
                }));
                assertThrows(IllegalArgumentException.class, () -> select(child, resource.key, selected));
                assertEquals(0, creations.get());
                assertFalse(child.containsBeanDefinition("springPaperDataSource"));
            }
        }
    }

    @Test void inheritedUnknownWrongProductIsValidatedByTheLaterTypedLookup() {
        for (var resource : RESOURCES) {
            var parent = new DefaultListableBeanFactory();
            var child = new DefaultListableBeanFactory(parent);
            var creations = new AtomicInteger();
            register(parent, child, resource, new RootBeanDefinition(UnknownFactory.class, () -> {
                creations.incrementAndGet(); return new UnknownFactory("wrong product");
            }));
            select(child, resource.key, "childAlias");
            assertEquals(0, creations.get());
            String reference = resource.reference == null ? "childAlias" : resource.reference;
            assertThrows(BeanNotOfRequiredTypeException.class, () -> child.getBean(reference, resource.type));
            assertEquals(1, creations.get());
        }
    }

    @Test void aGenericParentWithoutOwnerMetadataDefersItsTypeValidationWithoutProbingIt() {
        var parent = new DefaultListableBeanFactory();
        Object product = identity(EntityManagerFactory.class);
        parent.registerBeanDefinition("opaqueEntities", new RootBeanDefinition(UnknownFactory.class, () -> new UnknownFactory(product)));
        var typeProbes = new AtomicInteger();
        var opaque = (BeanFactory) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {BeanFactory.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("getType")) { typeProbes.incrementAndGet(); throw new AssertionError("No owner metadata is available"); }
            return method.invoke(parent, arguments);
        });
        var child = new DefaultListableBeanFactory(opaque);
        select(child, "spring.paper.jpa.entity-manager-factory-bean", "opaqueEntities");
        assertEquals(0, typeProbes.get());
        assertFalse(child.containsBeanDefinition("springPaperDataSource"));
        assertSame(product, child.getBean("entityManagerFactory", EntityManagerFactory.class));
    }

    private static void register(DefaultListableBeanFactory parent, DefaultListableBeanFactory child, Resource resource, RootBeanDefinition definition) {
        definition.setLazyInit(true);
        definition.setDefaultCandidate(false);
        parent.registerBeanDefinition("parentResource", definition);
        parent.registerAlias("parentResource", "parentAlias");
        child.registerAlias("parentAlias", "childAlias");
        if (resource.type == PlatformTransactionManager.class) parent.registerSingleton("existingEntities", identity(EntityManagerFactory.class));
    }
    private static void select(DefaultListableBeanFactory factory, String key, String name) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("selected", Map.of(key, name)));
        JpaConfiguration.springPaperJpaInfrastructure(new PaperConfiguration(environment)).postProcessBeanFactory(factory);
    }
    private static Object identity(Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
            if (method.getName().equals("equals")) return proxy == arguments[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return "owned " + type.getSimpleName();
            throw new AssertionError("Selection must not invoke resource methods: " + method);
        });
    }
}
