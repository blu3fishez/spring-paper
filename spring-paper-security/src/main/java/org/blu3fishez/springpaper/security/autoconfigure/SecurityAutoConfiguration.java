package org.blu3fishez.springpaper.security.autoconfigure;

import org.blu3fishez.springpaper.security.SecurityConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Preserves the existing opt-in configuration; dependency presence alone enables no resources. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.paper.security", name = "enabled", havingValue = "true", matchIfMissing = false)
@Import(SecurityConfiguration.class)
public class SecurityAutoConfiguration {}
