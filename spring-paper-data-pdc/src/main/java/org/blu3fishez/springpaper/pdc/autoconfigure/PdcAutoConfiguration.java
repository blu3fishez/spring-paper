package org.blu3fishez.springpaper.pdc.autoconfigure;

import org.blu3fishez.springpaper.pdc.internal.PdcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Preserves the existing opt-in configuration; dependency presence alone enables no resources. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.paper.pdc", name = "enabled", havingValue = "true", matchIfMissing = false)
@Import(PdcConfiguration.class)
public class PdcAutoConfiguration {}
