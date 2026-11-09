package org.blu3fishez.springpaper.example;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.blu3fishez.springpaper.pdc.EnablePaperPdcRepositories;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = ExampleConfiguration.class)
@EnablePaperPdcRepositories(basePackageClasses = ExampleConfiguration.class)
public class ExampleConfiguration {}
