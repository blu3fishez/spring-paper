package org.blu3fishez.springpaper.jdbc.autoconfigure;

import org.blu3fishez.springpaper.jdbc.JdbcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Preserves the existing opt-in configuration; dependency presence alone enables no resources. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.paper.jdbc", name = "enabled", havingValue = "true", matchIfMissing = false)
@Import(JdbcConfiguration.class)
public class JdbcAutoConfiguration {}
