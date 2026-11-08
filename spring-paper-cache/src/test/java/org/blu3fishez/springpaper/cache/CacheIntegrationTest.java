package org.blu3fishez.springpaper.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CacheIntegrationTest {
    public static class Queries {
        final AtomicInteger reads = new AtomicInteger();
        @Cacheable("notes") public int value(String id) { return reads.incrementAndGet(); }
    }
    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.paper.cache.names", "notes", "spring.paper.cache.spec", "maximumSize=100,expireAfterWrite=1m")));
        context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
        context.registerBean(Queries.class);
        context.register(CacheConfiguration.class);
        context.refresh();
        return context;
    }
    @Test void cachesThroughTheRealSpringProxy() {
        try (var context = context()) {
            Queries queries = context.getBean(Queries.class);
            assertEquals(1, queries.value("one"));
            assertEquals(1, queries.value("one"));
            assertEquals(2, queries.value("two"));
        }
    }
    @Test void defersMutationsUntilCommitAndDiscardsRollback() {
        try (var context = context()) {
            var cache = context.getBean(CacheManager.class).getCache("notes");
            assertNotNull(cache);
            cache.put("one", "original");
            var transaction = new TransactionTemplate(new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
                @Override protected void doCommit(DefaultTransactionStatus status) {}
                @Override protected void doRollback(DefaultTransactionStatus status) {}
            });
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                cache.evict("one");
                throw new IllegalStateException("rollback");
            }));
            assertEquals("original", cache.get("one", String.class));
            transaction.executeWithoutResult(status -> cache.evict("one"));
            assertNull(cache.get("one"));
        }
    }
}
