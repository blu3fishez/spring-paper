package org.blu3fishez.springpaper.examples.optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OptionalModuleConfiguration {
    @Bean String exampleModuleBanner() { return "module=enabled"; }
}
