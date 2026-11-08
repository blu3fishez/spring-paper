package org.blu3fishez.springpaper.fixture.resources;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

@Configuration
@ComponentScan(basePackageClasses = ResourceCommand.class)
@EnableCaching(proxyTargetClass = true)
public class ResourceConfiguration {
    @Bean public CacheManager caches() { return new ConcurrentMapCacheManager("commands"); }
}
