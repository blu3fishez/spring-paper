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
    @Configuration(proxyBeanMethods = false)
    static class Qualified {
        @Bean(defaultCandidate = false) CacheManager reportingCache() { return new ConcurrentMapCacheManager("reporting"); }
        @Bean Consumer consumer(CacheManager manager) { return new Consumer(manager); }
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
        }
    }
}
