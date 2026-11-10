package org.blu3fishez.springpaper.examples.business;

import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.blu3fishez.springpaper.examples.business.inbox.InboxConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Import({PaperCoreConfiguration.class, BusinessWorkerConfiguration.class, InboxConfiguration.class})
@EnableScheduling
public class BusinessConfiguration {}
