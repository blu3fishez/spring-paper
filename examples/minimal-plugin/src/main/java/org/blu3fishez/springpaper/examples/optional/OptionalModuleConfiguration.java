package org.blu3fishez.springpaper.examples.optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

// The SPI loader is only one registration path. The configuration also guards direct import/scan.
@Configuration(proxyBeanMethods = false)
@Conditional(ExampleModuleEnabled.class)
public class OptionalModuleConfiguration {
    @Bean String exampleModuleBanner() { return "module=enabled"; }
}
