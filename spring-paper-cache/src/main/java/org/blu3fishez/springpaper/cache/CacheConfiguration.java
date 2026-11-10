package org.blu3fishez.springpaper.cache;

import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.config.internal.DefaultBeans;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration {
    @Bean @Lazy @Fallback
    public CacheManager paperDefaultCacheManager(PaperConfiguration configuration) {
        var names = configuration.strings("spring.paper.cache.names");
        if (names.isEmpty()) throw new IllegalArgumentException("spring.paper.cache.names is required for the default Caffeine manager");
        var manager = new CaffeineCacheManager(names.toArray(String[]::new));
        manager.setCacheSpecification(configuration.required("spring.paper.cache.spec"));
        manager.setAllowNullValues(false);
        // Spring owns afterCommit mutation and CachingConfigurer resolver/key selection.
        // Keep this provider lazy: a consumer resolver must not require unused Caffeine settings.
        return new TransactionAwareCacheManagerProxy(manager);
    }
    @Bean public static BeanFactoryPostProcessor springPaperCacheInfrastructure() {
        return factory -> DefaultBeans.backOff(factory, CacheManager.class, "paperDefaultCacheManager", "cacheManager");
    }
}
