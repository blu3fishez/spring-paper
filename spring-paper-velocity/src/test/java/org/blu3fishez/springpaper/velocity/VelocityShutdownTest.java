package org.blu3fishez.springpaper.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class VelocityShutdownTest {
    @Test void shutdownBeforeInitializationPreventsContextCreation() {
        var host = new VelocityHost();
        var initializers = new AtomicInteger();
        var application = host.application(VelocityApplicationTest.HostConfiguration.class,
                context -> initializers.incrementAndGet());
        var shutdown = application.shutdown();
        VelocityHost.execute(application.initialize());
        VelocityHost.execute(shutdown);
        assertFalse(application.isReady());
        assertEquals(0, initializers.get());
        verifyNoInteractions(host.events, host.commands);
    }

    @Test void shutdownDuringRefreshWaitsAndPreventsLateInputsAndReadiness() throws Exception {
        var host = new VelocityHost();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var destroyed = new AtomicInteger();
        var context = new AtomicReference<ConfigurableApplicationContext>();
        var application = host.application(VelocityInputTest.InputConfiguration.class, captured -> {
            context.set(captured);
            captured.getBeanFactory().registerSingleton("fixture", host);
            ((org.springframework.context.annotation.AnnotationConfigApplicationContext) captured)
                    .registerBean("blockedResource", BlockedResource.class,
                            () -> new BlockedResource(entered, release, destroyed));
        });
        var startup = CompletableFuture.runAsync(() -> VelocityHost.execute(application.initialize()));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var shutdown = application.shutdown();
        var close = CompletableFuture.runAsync(() -> VelocityHost.execute(shutdown));
        try {
            assertFalse(application.isReady());
            assertFalse(close.isDone());
            assertTrue(host.listeners.isEmpty());
        } finally { release.countDown(); }
        assertThrows(java.util.concurrent.ExecutionException.class, () -> startup.get(2, TimeUnit.SECONDS));
        close.get(2, TimeUnit.SECONDS);
        assertEquals(1, destroyed.get());
        assertFalse(application.isReady());
        assertFalse(context.get().isActive());
        assertTrue(host.listeners.isEmpty());
        assertTrue(host.commandMetas.isEmpty());
    }

    @Test void standardSchedulerAndScheduledTasksStopWhenNativeShutdownTaskCompletes() throws Exception {
        var host = new VelocityHost();
        var context = new AtomicReference<ConfigurableApplicationContext>();
        var application = host.application(ScheduledConfiguration.class, context::set);
        VelocityHost.execute(application.initialize());
        var pulse = context.get().getBean(Pulse.class);
        assertTrue(pulse.called.await(2, TimeUnit.SECONDS));
        var scheduler = context.get().getBean(ThreadPoolTaskScheduler.class);
        var executor = scheduler.getScheduledThreadPoolExecutor();
        var delayed = scheduler.schedule(() -> fail("delayed work must not run after shutdown"), Instant.now().plus(Duration.ofDays(1)));
        VelocityHost.execute(application.shutdown());
        assertTrue(executor.isShutdown());
        assertTrue(delayed.isCancelled());
        assertThrows(org.springframework.core.task.TaskRejectedException.class, () -> scheduler.schedule(() -> {}, Instant.now()));
        assertFalse(context.get().isActive());
    }

    @Test void shutdownDuringNativeRegistrationUnregistersTheAcquiredListenerAndClosesContext() throws Exception {
        var host = new VelocityHost();
        var nativeEntered = new CountDownLatch(1);
        var releaseNative = new CountDownLatch(1);
        var shutdownInvoked = new CountDownLatch(1);
        var context = new AtomicReference<ConfigurableApplicationContext>();
        doAnswer(call -> {
            host.listeners.add(call.getArgument(1));
            nativeEntered.countDown();
            if (!releaseNative.await(2, TimeUnit.SECONDS)) throw new AssertionError("native register not released");
            return null;
        }).when(host.events).register(same(host.plugin), any());
        var application = host.application(VelocityInputTest.InputConfiguration.class, captured -> {
            context.set(captured);
            captured.getBeanFactory().registerSingleton("fixture", host);
        });
        var startup = CompletableFuture.runAsync(() -> VelocityHost.execute(application.initialize()));
        assertTrue(nativeEntered.await(2, TimeUnit.SECONDS));
        var shutdownTask = CompletableFuture.supplyAsync(() -> {
            shutdownInvoked.countDown();
            return application.shutdown();
        });
        assertTrue(shutdownInvoked.await(2, TimeUnit.SECONDS));
        try {
            assertThrows(java.util.concurrent.TimeoutException.class, () -> shutdownTask.get(50, TimeUnit.MILLISECONDS));
            assertFalse(application.isReady());
        } finally { releaseNative.countDown(); }
        assertThrows(java.util.concurrent.ExecutionException.class, () -> startup.get(2, TimeUnit.SECONDS));
        VelocityHost.execute(shutdownTask.get(2, TimeUnit.SECONDS));
        assertTrue(host.listeners.isEmpty());
        assertTrue(host.commandMetas.isEmpty());
        assertFalse(context.get().isActive());
        assertFalse(application.isReady());
        verify(host.events).unregisterListener(same(host.plugin), any());
    }

    @Test void partialSpringStartStillDestroysCreatedResourceWithoutRegisteringInputs() {
        var host = new VelocityHost();
        var destroyed = new AtomicInteger();
        var application = host.application(FailingLifecycleConfiguration.class, context -> {
            context.getBeanFactory().registerSingleton("fixture", host);
            ((org.springframework.context.annotation.AnnotationConfigApplicationContext) context)
                    .registerBean("managedResource", VelocityApplicationTest.ManagedResource.class,
                            () -> new VelocityApplicationTest.ManagedResource(destroyed));
        });
        assertThrows(RuntimeException.class, () -> VelocityHost.execute(application.initialize()));
        assertEquals(1, destroyed.get());
        assertFalse(application.isReady());
        verifyNoInteractions(host.events, host.commands);
        VelocityHost.execute(application.shutdown());
    }

    static class BlockedResource implements org.springframework.beans.factory.DisposableBean {
        final AtomicInteger destroyed;
        BlockedResource(CountDownLatch entered, CountDownLatch release, AtomicInteger destroyed) {
            this.destroyed = destroyed;
            entered.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("refresh not released"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
        @Override public void destroy() { destroyed.incrementAndGet(); }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class ScheduledConfiguration {
        @Bean ThreadPoolTaskScheduler taskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setDaemon(true);
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            return scheduler;
        }
        @Bean Pulse pulse() { return new Pulse(); }
    }
    static class Pulse {
        final CountDownLatch called = new CountDownLatch(1);
        @Scheduled(fixedDelay = 10) public void pulse() { called.countDown(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class FailingLifecycleConfiguration {
        @Bean org.springframework.context.SmartLifecycle failure() {
            return new org.springframework.context.SmartLifecycle() {
                @Override public void start() { throw new IllegalStateException("partial lifecycle failure"); }
                @Override public void stop() {}
                @Override public boolean isRunning() { return false; }
            };
        }
    }
}
