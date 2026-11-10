package org.blu3fishez.springpaper.examples.scheduling;

import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Import(PaperCoreConfiguration.class)
@ComponentScan(basePackageClasses = SchedulingConfiguration.class)
@EnableScheduling
@org.springframework.scheduling.annotation.EnableAsync
public class SchedulingConfiguration {}
