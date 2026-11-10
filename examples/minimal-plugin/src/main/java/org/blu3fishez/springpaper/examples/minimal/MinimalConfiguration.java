package org.blu3fishez.springpaper.examples.minimal;

import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(PaperCoreConfiguration.class)
@ComponentScan(basePackageClasses = MinimalConfiguration.class)
public class MinimalConfiguration {}
