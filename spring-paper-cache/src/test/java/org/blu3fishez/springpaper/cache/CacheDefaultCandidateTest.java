package org.blu3fishez.springpaper.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;

class CacheDefaultCandidateTest {
    record Consumer(CacheManager manager) {}
    public static class Queries {
        int calls;
        @org.springframework.cache.annotation.Cacheable("notes")
        public int read(String key) { return ++calls; }
    }
    @Configuration(proxyBeanMethods = false)
    static class Qualified {
        @Bean(defaultCandidate = false) CacheManager reportingCache() { return new ConcurrentMapCacheManager("reporting"); }
        @Bean Consumer consumer(CacheManager manager) { return new Consumer(manager); }
        @Bean Queries queries() { return new Queries(); }
    }
    @Test void qualifierOnlyManagerDoesNotSuppressTheDefaultOrBecomeTheApplicationCache() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cache", Map.of(
                    "spring.paper.cache.names", "notes", "spring.paper.cache.spec", "maximumSize=10")));
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.register(CacheConfiguration.class, Qualified.class);
            context.refresh();
            var manager = context.getBean(Consumer.class).manager();
            assertNotSame(context.getBean("reportingCache"), manager);
            assertNotNull(manager.getCache("notes"));
            assertNull(manager.getCache("reporting"));
            var queries = context.getBean(Queries.class);
            assertEquals(1, queries.read("one"));
            assertEquals(1, queries.read("one"));
            assertEquals(1, manager.getCache("notes").get("one", Integer.class));
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class ConsumerManagers {
        @Bean @Fallback CacheManager applicationCache() { return new ConcurrentMapCacheManager("notes"); }
        @Bean(defaultCandidate = false) CacheManager reportingCache() { return new ConcurrentMapCacheManager("reporting"); }
        @Bean Queries queries() { return new Queries(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class ExplicitCaching implements org.springframework.cache.annotation.CachingConfigurer {
        private final CacheManager manager;
        ExplicitCaching(@org.springframework.beans.factory.annotation.Qualifier("applicationCache") CacheManager manager) {
            this.manager = manager;
        }
        @Override public CacheManager cacheManager() { return manager; }
    }
    @Test void explicitConfigurerSelectsConsumerFallbackAlongsideDedicatedNonFallbackManager() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.register(CacheConfiguration.class, ConsumerManagers.class, ExplicitCaching.class);
            context.refresh();
            assertFalse(context.containsBean("paperDefaultCacheManager"));
            // Standard direct type lookup differs from descriptor injection in this consumer combination.
            assertSame(context.getBean("reportingCache"), context.getBean(CacheManager.class));
            var queries = context.getBean(Queries.class);
            assertEquals(1, queries.read("one"));
            assertEquals(1, queries.read("one"));
            assertEquals(1, context.getBean("applicationCache", CacheManager.class).getCache("notes").get("one", Integer.class));
        }
    }

}
