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
            var definition = new RootBeanDefinition(CacheManager.class, () -> {
                var names = configuration.strings("spring.paper.cache.names");
                if (names.isEmpty()) throw new IllegalArgumentException("spring.paper.cache.names is required for the default Caffeine manager");
                String spec = configuration.required("spring.paper.cache.spec");
                CaffeineCacheManager manager = new CaffeineCacheManager(names.toArray(String[]::new));
                manager.setCacheSpecification(spec);
                manager.setAllowNullValues(false);
                // 직접 put/evict 시점을 재구현하면 rollback된 값이 캐시에 남을 수 있다.
                // Spring의 TransactionAwareCacheDecorator가 afterCommit에 반영하도록 위임한다.
                return new TransactionAwareCacheManagerProxy(manager);
            });
            // Spring은 CachingConfigurer의 CacheResolver/KeyGenerator 선택을 직접 소유한다.
            // 정의 단계에서 Caffeine 설정을 요구하거나 기본 manager를 eager 생성하면, 소비자가
            // 표준 resolver만 제공한 정상 구성도 막힌다. 기본값은 Spring이 실제 선택할 때만 만든다.
            definition.setLazyInit(true);
            ((BeanDefinitionRegistry) factory).registerBeanDefinition("cacheManager", definition);
        };
    }
}
