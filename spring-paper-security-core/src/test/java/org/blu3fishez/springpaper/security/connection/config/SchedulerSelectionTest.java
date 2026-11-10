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
            assertNotSame(specialized, context.getBean("paperSecurityScheduler"));
            assertNotNull(context.getBean(PaperConnectionSecurityService.class));
        }
    }

    @Test void consumerMaintenanceSchedulerCannotTakeOverAuthenticationDeadlines() {
        var background = new ManualSecurityTime();
        var specialized = new ManualSecurityTime();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("background", ManualSecurityTime.class, () -> background);
            context.registerBean("specializedScheduler", TaskScheduler.class, () -> specialized,
                    definition -> ((AbstractBeanDefinition) definition).setDefaultCandidate(false));
            context.register(PaperConnectionSecurityConfiguration.class);
            context.refresh();
            assertTrue(context.containsBean("paperSecurityScheduler"));
            assertNotSame(background, context.getBean("paperSecurityScheduler"));
            assertSame(background, context.getBean(TaskScheduler.class));
            assertSame(background, context.getBean(Clock.class));
            var connection = PaperSecurityConnection.create(java.util.UUID.randomUUID(), "Consumer");
            // The unconfigured chain denies immediately; its timers use the dedicated security bean.
            var session = context.getBean(PaperConnectionSecurityService.class).open(new PaperSecurityRequest(
                    connection, PaperSecurityRequest.Stage.CONNECT, null)).toCompletableFuture().join();
            assertNotNull(session);
        }
    }
    @Test void explicitNamedOverrideRemainsOwnedByTheConsumer() {
        var backend = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        var scheduler = new org.springframework.scheduling.concurrent.ConcurrentTaskScheduler(backend);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PaperConnectionSecurityConfiguration.SCHEDULER_BEAN_NAME, TaskScheduler.class,
                    () -> scheduler, definition -> definition.setDestroyMethodName(""));
            context.register(PaperConnectionSecurityConfiguration.class);
            context.refresh();
            assertSame(scheduler, context.getBean(PaperConnectionSecurityConfiguration.SCHEDULER_BEAN_NAME));
            assertNotNull(context.getBean(PaperConnectionSecurityService.class));
        } finally {
            assertFalse(backend.isShutdown(), "the external backend's owner controls shutdown");
            backend.shutdownNow();
        }
    }
    @Test void blockedMaintenanceCannotDelaySecurityExpiry() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var expired = new java.util.concurrent.CompletableFuture<String>();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("maintenanceScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class,
                    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::new);
            context.registerBean(PaperSecuritySettings.class, () -> new PaperSecuritySettings(
                    java.time.Duration.ofMillis(100), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)));
            context.registerBean(org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain.class,
                    () -> new PaperSecurity().addFilter((exchange, next) -> java.util.concurrent.CompletableFuture.completedFuture(
                            org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision.pending(
                                    new org.blu3fishez.springpaper.security.connection.domain.PaperAuthenticationChallenge("approval", java.util.Map.of()), null))).build());
            context.registerBean(org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler.class,
                    () -> (session, error) -> {
                        if (session.status() == org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession.Status.EXPIRED)
                            expired.complete(session.reason());
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    });
            context.register(PaperConnectionSecurityConfiguration.class);
            context.refresh();
            context.getBean("maintenanceScheduler", TaskScheduler.class).schedule(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, java.time.Instant.now());
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var connection = PaperSecurityConnection.create(java.util.UUID.randomUUID(), "Waiting");
                var pending = context.getBean(PaperConnectionSecurityService.class).open(new PaperSecurityRequest(
                        connection, PaperSecurityRequest.Stage.CONNECT, null)).toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession.Status.PENDING, pending.status());
                assertEquals("expired", expired.get(2, java.util.concurrent.TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }
}
