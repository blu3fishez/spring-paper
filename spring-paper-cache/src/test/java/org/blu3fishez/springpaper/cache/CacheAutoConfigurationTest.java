package org.blu3fishez.springpaper.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class CacheAutoConfigurationTest {
    @Test void disabledModuleNeedsNoCaffeineSettingsAndCreatesNoCacheManager() {
        for (Map<String, Object> properties : java.util.List.of(Map.<String, Object>of(),
                Map.<String, Object>of("spring.paper.cache.enabled", false))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", properties));
                context.register(Root.class);
                context.refresh();
                assertTrue(context.getBeansOfType(CacheManager.class).isEmpty());
            }
        }
    }

    @Test void enabledImportsReuseTheConsumerManagerAndRealCachingAdviceWithoutDefaultProviderSettings() {
        var supplied = new ConsumerManager();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("spring.paper.cache.enabled", true)));
            context.getBeanFactory().registerSingleton("consumerManager", supplied);
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.register(Root.class, Queries.class);
            context.refresh();
            assertSame(supplied, context.getBean(CacheManager.class));
            assertEquals(1, context.getBeansOfType(CacheManager.class).size());
            var queries = context.getBean(Queries.class);
            assertEquals(1, queries.value("one"));
            assertEquals(1, queries.value("one"));
        }
        assertEquals(0, supplied.closes.get());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Root {}

    public static class Queries {
        final AtomicInteger reads = new AtomicInteger();
        @Cacheable("notes") public int value(String key) { return reads.incrementAndGet(); }
    }

    private static final class ConsumerManager extends ConcurrentMapCacheManager implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();
        ConsumerManager() { super("notes"); }
        @Override public void close() { closes.incrementAndGet(); }
    }
}
