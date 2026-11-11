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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
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
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.beans.factory.support.AbstractBeanDefinition;

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
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class WithoutConsumerScheduler {
        @Bean Probe scheduledProbe() { return new Probe(); }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class WithConsumerExecutor {
        @Bean(destroyMethod = "shutdown") ScheduledExecutorService consumerTimer() {
            return Executors.newSingleThreadScheduledExecutor(action -> {
                Thread thread = new Thread(action, "consumer-timer"); thread.setDaemon(true); return thread;
            });
        }
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

    @Test void unqualifiedScheduledKeepsBackgroundExecutionWithoutAConsumerScheduler() throws Exception {
        var host = new Host();
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, host.plugin, new PaperConfiguration(context.getEnvironment()));
            context.register(WithoutConsumerScheduler.class);
            context.refresh();
            var probe = context.getBean(Probe.class);
            assertNotSame(Thread.currentThread(), probe.background.get(2, TimeUnit.SECONDS));
            host.take().run();
            assertSame(Thread.currentThread(), probe.main.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void consumerScheduledExecutorIsReusedAndRemainsOwnedByItsBean() throws Exception {
        var host = new Host();
        ScheduledExecutorService executor;
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, host.plugin, new PaperConfiguration(context.getEnvironment()));
            context.register(WithConsumerExecutor.class);
            context.refresh();
            executor = context.getBean(ScheduledExecutorService.class);
            assertInstanceOf(ConcurrentTaskScheduler.class, context.getBean(TaskScheduler.class));
            var probe = context.getBean(Probe.class);
            assertEquals("consumer-timer", probe.background.get(5, TimeUnit.SECONDS).getName());
        }
        assertTrue(executor.isShutdown());
    }

    @Test void consumerFallbackSchedulerAlsoReplacesTheLibraryBackgroundDefault() throws Exception {
        var host = new Host();
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, host.plugin, new PaperConfiguration(context.getEnvironment()));
            context.registerBean("consumerFallbackScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new,
                    definition -> ((AbstractBeanDefinition) definition).setFallback(true));
            context.register(WithoutConsumerScheduler.class);
            context.refresh();
            assertFalse(context.containsBean(PaperCoreConfiguration.DEFAULT_SCHEDULER_BEAN_NAME));
            assertSame(context.getBean("consumerFallbackScheduler"), context.getBean(TaskScheduler.class));
            assertNotSame(Thread.currentThread(), context.getBean(Probe.class).background.get(5, TimeUnit.SECONDS));
        }
    }

    public static class SchedulerFactory implements org.springframework.beans.factory.FactoryBean<TaskScheduler>, org.springframework.beans.factory.DisposableBean {
        private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        public SchedulerFactory() { scheduler.setThreadNamePrefix("consumer-factory-"); scheduler.initialize(); }
        @Override public TaskScheduler getObject() { return scheduler; }
        @Override public Class<?> getObjectType() { return TaskScheduler.class; }
        @Override public void destroy() { scheduler.destroy(); }
    }

    @Test void factoryProductFallbackSchedulerReplacesTheLibraryBackgroundDefault() throws Exception {
        var host = new Host();
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, host.plugin, new PaperConfiguration(context.getEnvironment()));
            context.registerBean("consumerFallbackScheduler", SchedulerFactory.class, SchedulerFactory::new,
                    definition -> ((AbstractBeanDefinition) definition).setFallback(true));
            context.register(WithoutConsumerScheduler.class);
            context.refresh();
            assertFalse(context.containsBean(PaperCoreConfiguration.DEFAULT_SCHEDULER_BEAN_NAME));
            assertSame(context.getBean("consumerFallbackScheduler"), context.getBean(TaskScheduler.class));
            assertTrue(context.getBean(Probe.class).background.get(5, TimeUnit.SECONDS).getName().startsWith("consumer-factory-"));
        }
    }

    @Test void qualifierOnlySchedulerFactoryDoesNotSuppressTheBackgroundDefaultOrInitializeDuringSelection() throws Exception {
        var host = new Host();
        var creations = new AtomicInteger();
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, host.plugin, new PaperConfiguration(context.getEnvironment()));
            context.registerBean("dedicatedScheduler", SchedulerFactory.class, () -> {
                creations.incrementAndGet(); return new SchedulerFactory();
            }, definition -> {
                ((AbstractBeanDefinition) definition).setDefaultCandidate(false);
                definition.setLazyInit(true);
            });
            context.addBeanFactoryPostProcessor(beans -> {
                PaperCoreConfiguration.paperSchedulingDefaults().postProcessBeanFactory(beans);
                assertEquals(0, creations.get());
            });
            context.register(WithoutConsumerScheduler.class);
            context.refresh();
            assertTrue(context.containsBean(PaperCoreConfiguration.DEFAULT_SCHEDULER_BEAN_NAME));
            var selected = context.getBeanProvider(TaskScheduler.class).getIfAvailable();
            assertSame(context.getBean(PaperCoreConfiguration.DEFAULT_SCHEDULER_BEAN_NAME), selected);
            assertTrue(context.getBean(Probe.class).background.get(5, TimeUnit.SECONDS).getName()
                    .startsWith("spring-paper-scheduler-"));
        }
    }

    @Test void primaryFactorySchedulerIsSelectedAmongConsumerCandidatesWithoutCreatingProductsDuringSelection() throws Exception {
        var host = new Host();
        var creations = new AtomicInteger();
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, host.plugin, new PaperConfiguration(context.getEnvironment()));
            context.registerBean("primaryScheduler", SchedulerFactory.class, () -> {
                creations.incrementAndGet();
                var factory = new SchedulerFactory();
                factory.scheduler.setThreadNamePrefix("primary-factory-");
                return factory;
            }, definition -> definition.setPrimary(true));
            context.registerBean("otherScheduler", SchedulerFactory.class, SchedulerFactory::new);
            context.addBeanFactoryPostProcessor(beans -> assertEquals(0, creations.get()));
            context.register(WithoutConsumerScheduler.class);
            context.refresh();
            assertFalse(context.containsBean(PaperCoreConfiguration.DEFAULT_SCHEDULER_BEAN_NAME));
            assertSame(context.getBean("primaryScheduler"), context.getBean(TaskScheduler.class));
            assertTrue(context.getBean(Probe.class).background.get(5, TimeUnit.SECONDS).getName().startsWith("primary-factory-"));
        }
    }

    static class SelectedProbe {
        final CompletableFuture<String> execution = new CompletableFuture<>();
        @Scheduled(scheduler = "consumerFallbackScheduler", initialDelay = 1, fixedDelay = 60000)
        public void selected() { execution.complete(Thread.currentThread().getName()); }
    }

    @Test void explicitSchedulerSelectsConsumerFallbackAlongsideDedicatedNonFallbackTimer() throws Exception {
        var host = new Host();
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, host.plugin, new PaperConfiguration(context.getEnvironment()));
            context.registerBean("consumerFallbackScheduler", SchedulerFactory.class, SchedulerFactory::new,
                    definition -> ((AbstractBeanDefinition) definition).setFallback(true));
            context.registerBean("dedicatedScheduler", SchedulerFactory.class, () -> {
                var factory = new SchedulerFactory();
                factory.scheduler.setThreadNamePrefix("dedicated-factory-");
                return factory;
            }, definition -> ((AbstractBeanDefinition) definition).setDefaultCandidate(false));
            context.registerBean(SelectedProbe.class);
            context.register(WithoutConsumerScheduler.class);
            context.refresh();
            assertFalse(context.containsBean(PaperCoreConfiguration.DEFAULT_SCHEDULER_BEAN_NAME));
            // Consumer-provided Fallback metadata retains Spring's direct type-lookup semantics.
            assertTrue(context.getBean(Probe.class).background.get(5, TimeUnit.SECONDS).getName().startsWith("dedicated-factory-"));
            assertTrue(context.getBean(SelectedProbe.class).execution.get(5, TimeUnit.SECONDS).startsWith("consumer-factory-"));
        }
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
