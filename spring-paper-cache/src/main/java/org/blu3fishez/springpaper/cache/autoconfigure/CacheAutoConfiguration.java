package org.blu3fishez.springpaper.cache.autoconfigure;

import org.blu3fishez.springpaper.cache.CacheConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Preserves the existing opt-in configuration; dependency presence alone enables no resources. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.paper.cache", name = "enabled", havingValue = "true", matchIfMissing = false)
@Import(CacheConfiguration.class)
public class CacheAutoConfiguration {}
