package org.blu3fishez.springpaper.jpa;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

class JpaAutoConfigurationTest {
    @Test void disabledJpaRequiresNoEntityPackagesDatabaseOrPersistenceResources() {
        for (Map<String, Object> properties : java.util.List.of(Map.<String, Object>of(),
                Map.<String, Object>of("spring.paper.jpa.enabled", false))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", properties));
                context.register(Root.class);
                context.refresh();
                assertTrue(context.getBeansOfType(EntityManagerFactory.class).isEmpty());
                assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
                assertTrue(context.getBeansOfType(PlatformTransactionManager.class).isEmpty());
            }
        }
    }

    @Test void enabledImportsReuseExternalEntitiesWithoutCreatingAnUnusedPoolOrTakingOwnership() {
        var closes = new AtomicInteger();
        EntityManagerFactory supplied = (EntityManagerFactory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class}, (object, method, args) -> switch (method.getName()) {
                    case "close" -> { closes.incrementAndGet(); yield null; }
                    case "isOpen" -> closes.get() == 0;
                    case "toString" -> "consumer-owned-entities";
                    case "hashCode" -> System.identityHashCode(object);
                    case "equals" -> object == args[0];
                    default -> throw new AssertionError("Disabled default infrastructure must not operate external entities: " + method);
                });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("spring.paper.jpa.enabled", true)));
            context.getBeanFactory().registerSingleton("consumerEntities", supplied);
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.register(Root.class);
            context.refresh();
            assertSame(supplied, context.getBean("entityManagerFactory"));
            assertTrue(context.containsBeanDefinition("transactionManager"));
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        }
        assertEquals(0, closes.get());
        assertTrue(supplied.isOpen());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Root {}
}
