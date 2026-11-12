package org.blu3fishez.springpaper.jpa.autoconfigure;

import org.blu3fishez.springpaper.jpa.JpaConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Preserves the existing opt-in configuration; dependency presence alone enables no resources. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.paper.jpa", name = "enabled", havingValue = "true", matchIfMissing = false)
@Import(JpaConfiguration.class)
public class JpaAutoConfiguration {}
