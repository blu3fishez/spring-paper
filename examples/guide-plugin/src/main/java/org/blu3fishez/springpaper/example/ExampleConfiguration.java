package org.blu3fishez.springpaper.example;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = ExampleConfiguration.class)
public class ExampleConfiguration {}
