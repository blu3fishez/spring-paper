package org.blu3fishez.springpaper.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/** Consumer configurations use Spring contracts directly; no replacement cache API or host mock. */
class SpringCacheCustomizationTest {
    public static class Queries {
        final AtomicInteger reads = new AtomicInteger();
        @Cacheable(cacheNames = "notes", key = "#p0")
        public int read(String id) { return reads.incrementAndGet(); }
        @CachePut(cacheNames = "notes", key = "#p0")
        public int update(String id, int value) { return value; }
        @CacheEvict("notes")
        public void remove(String id) {}
    }
    public static class GeneratedKeys {
        final AtomicInteger reads = new AtomicInteger();
        @Cacheable("notes") public int read(String id) { return reads.incrementAndGet(); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class ConsumerManager {
        @Bean CacheManager applicationCacheManager() { return new ConcurrentMapCacheManager("notes"); }
    }
    @Configuration
    public static class ConsumerResolver implements CachingConfigurer {
        @Bean Cache applicationCache() { return new ConcurrentMapCache("notes"); }
        @Override @Bean public CacheResolver cacheResolver() {
            return request -> List.of(applicationCache());
        }
        @Override @Bean public KeyGenerator keyGenerator() {
            return (target, method, parameters) -> "application:" + parameters[0];
        }
    }

    @Test void consumerCacheManagerNeedsNoPaperProviderProperties() {
        try (var context = context(ConsumerManager.class, Map.of())) {
            var queries = context.getBean(Queries.class);
            assertEquals(1, queries.read("one"));
            assertEquals(1, queries.read("one"));
            assertEquals(2, queries.read("two"));
            assertEquals(1, context.getBeansOfType(CacheManager.class).size());
            assertEquals(1, context.getBean(CacheManager.class).getCache("notes").get("one", Integer.class));
        }
    }

    @Test void standardCachePutAndCacheEvictUpdateTheSameCacheableEntry() {
        try (var context = context(ConsumerManager.class, Map.of())) {
            var queries = context.getBean(Queries.class);
            assertEquals(1, queries.read("one"));
            assertEquals(42, queries.update("one", 42));
            assertEquals(42, queries.read("one"));
            queries.remove("one");
            assertEquals(2, queries.read("one"));
        }
    }

    @Test void cachingConfigurerResolverAndKeyGeneratorNeedNoCaffeineConfiguration() {
        try (var context = assertDoesNotThrow(() -> context(ConsumerResolver.class, Map.of()))) {
            verifyConsumerResolver(context);
        }
    }

    @Test void unusedDefaultProviderDoesNotOverrideOrValidateTheConsumerResolver() {
        try (var context = assertDoesNotThrow(() -> context(ConsumerResolver.class, Map.of(
                "spring.paper.cache.names", "unused",
                "spring.paper.cache.spec", "invalid-provider-spec")))) {
            verifyConsumerResolver(context);
        }
    }

    @Test void defaultProviderStillRequiresItsExplicitConfiguration() {
        assertThrows(RuntimeException.class, () -> context(null, Map.of()));
    }

    private void verifyConsumerResolver(AnnotationConfigApplicationContext context) {
        var queries = context.getBean(GeneratedKeys.class);
        assertEquals(1, queries.read("one"));
        assertEquals(1, queries.read("one"));
        assertEquals(2, queries.read("two"));
        var cache = context.getBean(Cache.class);
        assertEquals(1, cache.get("application:one", Integer.class));
        assertEquals(2, cache.get("application:two", Integer.class));
    }

    private AnnotationConfigApplicationContext context(Class<?> consumer, Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
        context.register(Queries.class, GeneratedKeys.class, CacheConfiguration.class);
        if (consumer != null) context.register(consumer);
        try { context.refresh(); return context; }
        catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }
}
