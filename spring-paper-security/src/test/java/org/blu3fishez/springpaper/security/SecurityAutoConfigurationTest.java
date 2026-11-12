package org.blu3fishez.springpaper.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SecurityAutoConfigurationTest {
    @Test void missingAndFalseFlagsStartNoSecurityServiceOrDedicatedScheduler() {
        for (Map<String, Object> properties : java.util.List.of(Map.<String, Object>of(),
                Map.<String, Object>of("spring.paper.security.enabled", false))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", properties));
                context.register(Root.class);
                context.refresh();
                assertTrue(context.getBeansOfType(PaperConnectionSecurityService.class).isEmpty());
                assertTrue(context.getBeansOfType(PaperSecurityInterceptor.class).isEmpty());
                assertTrue(context.getBeansOfType(ThreadPoolTaskScheduler.class).isEmpty());
            }
        }
    }

    @Test void trueFlagStartsTheExistingSecurityLifecycleAndStopsItsOwnedTimer() {
        ThreadPoolTaskScheduler scheduler;
        PaperConnectionSecurityService service;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("spring.paper.security.enabled", true)));
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.register(Root.class);
            context.refresh();
            service = context.getBean(PaperConnectionSecurityService.class);
            scheduler = context.getBean("paperSecurityScheduler", ThreadPoolTaskScheduler.class);
            assertTrue(service.isRunning());
            assertNotNull(context.getBean(PaperSecurityInterceptor.class));
        }
        assertFalse(service.isRunning());
        assertTrue(scheduler.getScheduledThreadPoolExecutor().isShutdown());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Root {}
}
