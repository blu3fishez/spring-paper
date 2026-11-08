package org.blu3fishez.springpaper.cache;

import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration {
    @Bean public static BeanFactoryPostProcessor springPaperCacheInfrastructure(PaperConfiguration configuration) {
        return factory -> {
            if (factory.getBeanNamesForType(CacheManager.class, false, false).length != 0) return;
            var names = configuration.strings("spring.paper.cache.names");
            if (names.isEmpty()) throw new IllegalArgumentException("spring.paper.cache.names is required for the default Caffeine manager");
            String spec = configuration.required("spring.paper.cache.spec");
            ((BeanDefinitionRegistry) factory).registerBeanDefinition("cacheManager",
                    new RootBeanDefinition(CacheManager.class, () -> {
                        CaffeineCacheManager manager = new CaffeineCacheManager(names.toArray(String[]::new));
                        manager.setCacheSpecification(spec);
                        manager.setAllowNullValues(false);
                        return new TransactionAwareCacheManagerProxy(manager);
                    }));
        };
    }
}
