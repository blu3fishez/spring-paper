package org.blu3fishez.springpaper.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class PaperMainThreadTaskSchedulerTest {
    @Test void oneShotCompletesOnlyAfterMainExecutionAndRestoresTheHostLoader() throws Exception {
        try (var fixture = new Fixture()) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            ClassLoader consumer = new ClassLoader(previous) {};
            fixture.scheduler.setBeanClassLoader(consumer);
            var thread = new AtomicReference<Thread>();
            var loader = new AtomicReference<ClassLoader>();
            var future = fixture.scheduler.schedule(() -> {
                thread.set(Thread.currentThread());
                loader.set(Thread.currentThread().getContextClassLoader());
            }, Instant.now());
            Delivery delivery = fixture.host.take();
            assertFalse(future.isDone());
            assertNull(thread.get());
            delivery.run();
            future.get(5, TimeUnit.SECONDS);
            assertSame(Thread.currentThread(), thread.get());
            assertSame(consumer, loader.get());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
        }
    }

    @Test void fixedDelayStartsAfterPaperCompletionAndDoesNotQueueWhilePaperIsStalled() throws Exception {
        try (var fixture = new Fixture()) {
            var calls = new AtomicInteger();
            var finished = new AtomicReference<Instant>();
            Duration delay = Duration.ofMillis(50);
            var future = fixture.scheduler.scheduleWithFixedDelay(() -> {
                calls.incrementAndGet();
                finished.set(Instant.now());
            }, delay);
            Delivery first = fixture.host.take();
            assertNull(fixture.host.deliveries.poll(150, TimeUnit.MILLISECONDS));
            assertEquals(0, calls.get());
            first.run();
            Delivery second = fixture.host.take();
            assertFalse(second.enqueuedAt.isBefore(finished.get().plus(delay)));
            assertEquals(1, calls.get());
            assertTrue(future.cancel(false));
            second.run(); // Even a stale host callback must not execute a cancelled user action.
            assertEquals(1, calls.get());
        }
    }

    @Test void fixedRateAlsoKeepsAtMostOnePendingMainCallback() throws Exception {
        try (var fixture = new Fixture()) {
            var calls = new AtomicInteger();
            var future = fixture.scheduler.scheduleAtFixedRate(calls::incrementAndGet,
                    Instant.now(), Duration.ofMillis(10));
            Delivery first = fixture.host.take();
            assertNull(fixture.host.deliveries.poll(80, TimeUnit.MILLISECONDS));
            assertTrue(future.cancel(false));
            first.run();
            assertEquals(0, calls.get());
            assertTrue(future.isCancelled());
        }
    }

    @Test void cancellingAQueuedOneShotFencesItsCallbackWithoutInterruptingPaper() throws Exception {
        try (var fixture = new Fixture()) {
            var calls = new AtomicInteger();
            var future = fixture.scheduler.schedule(calls::incrementAndGet, Instant.now());
            Delivery delivery = fixture.host.take();
            assertTrue(future.cancel(true));
            delivery.run();
            assertEquals(0, calls.get());
            assertFalse(Thread.currentThread().isInterrupted());
            assertThrows(java.util.concurrent.CancellationException.class, future::get);
        }
    }

    @Test void shutdownCancelsPendingAndDelayedWorkAndLeavesWorkersOwnedByPaperTasks() throws Exception {
        try (var fixture = new Fixture()) {
            var calls = new AtomicInteger();
            var pending = fixture.scheduler.schedule(calls::incrementAndGet, Instant.now());
            Delivery delivery = fixture.host.take();
            var delayed = fixture.scheduler.schedule(calls::incrementAndGet, Instant.now().plusSeconds(60));
            fixture.scheduler.close();
            delivery.run();
            assertTrue(pending.isCancelled());
            assertTrue(delayed.isCancelled());
            assertEquals(0, calls.get());
            assertThrows(TaskRejectedException.class,
                    () -> fixture.scheduler.schedule(calls::incrementAndGet, Instant.now()));
            assertEquals("worker still owned by PaperTasks", fixture.tasks.async(() -> "worker still owned by PaperTasks")
                    .get(5, TimeUnit.SECONDS));
        }
    }

    @Test void springTriggerSeesPaperCompletionAndMayDeclineAnyExecution() throws Exception {
        try (var fixture = new Fixture()) {
            assertNull(fixture.scheduler.schedule(() -> fail("No execution requested"), context -> null));
            var calls = new AtomicInteger();
            var finished = new AtomicReference<Instant>();
            var observedCompletion = new AtomicReference<Instant>();
            var future = fixture.scheduler.schedule(() -> finished.set(Instant.now()), context -> {
                if (calls.getAndIncrement() == 0) return context.getClock().instant();
                observedCompletion.set(context.lastCompletion());
                return null;
            });
            fixture.host.take().run();
            future.get(5, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
            assertFalse(observedCompletion.get().isBefore(finished.get()));
        }
    }

    @Test void oneShotFailureReachesItsFutureAndAnotherTaskStillRuns() throws Exception {
        try (var fixture = new Fixture()) {
            var failure = new IllegalArgumentException("consumer failure");
            var failed = fixture.scheduler.schedule(() -> { throw failure; }, Instant.now());
            fixture.host.take().run();
            assertSame(failure, assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS)).getCause());
            var calls = new AtomicInteger();
            var next = fixture.scheduler.schedule(calls::incrementAndGet, Instant.now());
            fixture.host.take().run();
            next.get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Consumer {
        @Bean ThreadPoolTaskScheduler backgroundScheduler() { return new ThreadPoolTaskScheduler(); }
        @Bean Probe scheduledProbe() { return new Probe(); }
    }
    static class Probe {
        final CompletableFuture<Thread> background = new CompletableFuture<>();
        final CompletableFuture<Thread> main = new CompletableFuture<>();
        @Scheduled(initialDelayString = "PT0.01S", fixedDelayString = "PT60S")
        public void background() { background.complete(Thread.currentThread()); }
        @Scheduled(scheduler = PaperMainThreadTaskScheduler.BEAN_NAME,
                initialDelayString = "PT0.01S", fixedDelayString = "PT60S")
        public void main() { main.complete(Thread.currentThread()); }
    }

    @Test void standardScheduledUsesTheRealNamedBeanAndUnqualifiedJobsKeepTheConsumerScheduler() throws Exception {
        var host = new Host();
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, host.plugin, new PaperConfiguration(context.getEnvironment()));
            context.register(Consumer.class);
            context.refresh();
            assertSame(context.getBean("backgroundScheduler"), context.getBean(TaskScheduler.class));
            Probe probe = context.getBean(Probe.class);
            assertNotSame(Thread.currentThread(), probe.background.get(5, TimeUnit.SECONDS));
            host.take().run();
            assertSame(Thread.currentThread(), probe.main.get(5, TimeUnit.SECONDS));
            var pending = context.getBean(PaperMainThreadTaskScheduler.class)
                    .schedule(() -> fail("Closed context callback ran"), Instant.now());
            Delivery stale = host.take();
            context.close();
            stale.run();
            assertTrue(pending.isCancelled());
        }
    }

    private record Delivery(Runnable action, Instant enqueuedAt) {
        void run() { action.run(); }
    }
    private static final class Host {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
        Host() {
            Server server = mock(Server.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            Thread main = Thread.currentThread();
            when(plugin.getServer()).thenReturn(server);
            when(plugin.namespace()).thenReturn("schedulingtest");
            when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data/scheduling"));
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("paper-scheduling-test"));
            when(plugin.isEnabled()).thenReturn(true);
            when(server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == main);
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                deliveries.add(new Delivery(call.getArgument(1), Instant.now()));
                return mock(BukkitTask.class);
            });
        }
        Delivery take() throws InterruptedException {
            Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
            assertNotNull(delivery, "Timer did not dispatch a main-thread callback");
            return delivery;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Host host = new Host();
        final PaperTasks tasks = new PaperTasks(host.plugin, new PaperConfiguration(new StandardEnvironment()));
        final PaperMainThreadTaskScheduler scheduler = new PaperMainThreadTaskScheduler(tasks);
        @Override public void close() { scheduler.close(); tasks.close(); }
    }
}
