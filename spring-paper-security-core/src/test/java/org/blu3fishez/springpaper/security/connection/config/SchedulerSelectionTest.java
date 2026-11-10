package org.blu3fishez.springpaper.security.connection.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityRequest;
import org.blu3fishez.springpaper.security.connection.fixture.ManualSecurityTime;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;

class SchedulerSelectionTest {
    @Test void qualifierOnlySchedulerDoesNotSuppressTheBackgroundSecurityDefault() {
        var specialized = new ManualSecurityTime();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("specializedScheduler", TaskScheduler.class, () -> specialized,
                    definition -> ((AbstractBeanDefinition) definition).setDefaultCandidate(false));
            context.register(PaperConnectionSecurityConfiguration.class);
            context.refresh();
            assertTrue(context.containsBean("paperSecurityScheduler"));
            assertSame(context.getBean("paperSecurityScheduler"), context.getBean(TaskScheduler.class));
            assertNotNull(context.getBean(PaperConnectionSecurityService.class));
        }
    }

    @Test void explicitConsumerDefaultStillOwnsAuthenticationDeadlinesBesideASpecializedScheduler() {
        var background = new ManualSecurityTime();
        var specialized = new ManualSecurityTime();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("background", ManualSecurityTime.class, () -> background);
            context.registerBean("specializedScheduler", TaskScheduler.class, () -> specialized,
                    definition -> ((AbstractBeanDefinition) definition).setDefaultCandidate(false));
            context.register(PaperConnectionSecurityConfiguration.class);
            context.refresh();
            assertFalse(context.containsBean("paperSecurityScheduler"));
            assertSame(background, context.getBean(TaskScheduler.class));
            assertSame(background, context.getBean(Clock.class));
            var connection = PaperSecurityConnection.create(java.util.UUID.randomUUID(), "Consumer");
            // The unconfigured chain denies immediately; scheduling must still resolve the background bean.
            var session = context.getBean(PaperConnectionSecurityService.class).open(new PaperSecurityRequest(
                    connection, PaperSecurityRequest.Stage.CONNECT, null)).toCompletableFuture().join();
            assertNotNull(session);
        }
    }
}
