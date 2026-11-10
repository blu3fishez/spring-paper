package org.blu3fishez.springpaper.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.TaskExecutorAdapter;

class WorkerConfigurationTest {
    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    @Test void defaultIsVirtualPerTaskAndReusesTheConsumersDecoratorExactlyOnce() throws Exception {
        var applied = new AtomicInteger();
        try (var context = context()) {
            context.registerBean(TaskDecorator.class, () -> action -> {
                String value = CONTEXT.get();
                return () -> { applied.incrementAndGet(); CONTEXT.set(value); try { action.run(); } finally { CONTEXT.remove(); } };
            });
            context.refresh();
            var tasks = context.getBean(PaperTasks.class);
            CONTEXT.set("captured");
            assertEquals("captured:true", tasks.async(() -> CONTEXT.get() + ":" + Thread.currentThread().isVirtual()).get(2, TimeUnit.SECONDS));
            assertEquals(1, applied.get());
        } finally { CONTEXT.remove(); }
    }
    @Test void suppliedAsyncExecutorKeepsItsDecoratorAndExternalShutdownOwnership() throws Exception {
        var backend = Executors.newSingleThreadExecutor();
        var decorated = new AtomicInteger();
        var executor = new TaskExecutorAdapter(backend);
        executor.setTaskDecorator(action -> () -> { decorated.incrementAndGet(); action.run(); });
        try {
            try (var context = context()) {
                context.registerBean(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME, AsyncTaskExecutor.class,
                        () -> executor, definition -> definition.setDestroyMethodName(""));
                context.refresh();
                assertFalse(context.containsBean(PaperExecutionConfiguration.DEFAULT_WORKER_BEAN_NAME));
                assertEquals("consumer", context.getBean(PaperTasks.class).async(() -> "consumer").get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, decorated.get());
            assertEquals("still owned externally", backend.submit(() -> "still owned externally").get(2, TimeUnit.SECONDS));
        } finally { backend.shutdownNow(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class QualifiedFallback {
        @Bean(defaultCandidate = false) @Fallback
        @Qualifier(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME)
        Executor selectedWorker() { return Runnable::run; }
        @Bean Executor unrelatedExecutor() { return action -> fail("Unrelated main/timer executor was selected"); }
    }
    @Test void explicitRoleHonorsQualifierOnlyAndFallbackWithoutSelectingAnUnrelatedExecutor() {
        try (var context = context()) {
            context.register(QualifiedFallback.class); context.refresh();
            assertFalse(context.containsBean(PaperExecutionConfiguration.DEFAULT_WORKER_BEAN_NAME));
            assertSame(Thread.currentThread(), context.getBean(PaperTasks.class).async(Thread::currentThread).join());
        }
    }
    public static class WorkerFactory implements FactoryBean<Executor> {
        final AtomicInteger created = new AtomicInteger();
        @Override public Executor getObject() { created.incrementAndGet(); return Runnable::run; }
        @Override public Class<?> getObjectType() { return Executor.class; }
    }
    @Test void factoryBeanWorkerProductIsSelectedWithoutCreatingItDuringPostprocessing() {
        var factory = new WorkerFactory();
        try (var context = context()) {
            context.registerBean(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME, WorkerFactory.class, () -> factory);
            context.addBeanFactoryPostProcessor(beans -> assertEquals(0, factory.created.get()));
            context.refresh();
            assertFalse(context.containsBean(PaperExecutionConfiguration.DEFAULT_WORKER_BEAN_NAME));
            assertEquals("product", context.getBean(PaperTasks.class).async(() -> "product").join());
            assertEquals(1, factory.created.get());
        }
    }
    @Test void queuedManagedWorkerFuturesCompleteAtStopAndNeverRunOnLateDelivery() {
        var queue = new java.util.ArrayDeque<Runnable>();
        var configuration = new PaperConfiguration(new org.springframework.core.env.StandardEnvironment());
        try (var tasks = new PaperTasks(mock(JavaPlugin.class), configuration, queue::add)) {
            var called = new AtomicInteger();
            var pending = tasks.submitCompletable(called::incrementAndGet);
            tasks.close();
            assertTrue(pending.isCompletedExceptionally());
            queue.remove().run();
            assertEquals(0, called.get());
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class PrimaryWorker {
        @Bean @Primary @Qualifier(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME)
        Executor primaryWorker() { return Runnable::run; }
        @Bean @Fallback @Qualifier(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME)
        Executor fallbackWorker() { return action -> fail("Primary worker was ignored"); }
    }
    @Test void springSelectsThePrimaryAmongConsumerRoleCandidates() {
        try (var context = context()) {
            context.register(PrimaryWorker.class); context.refresh();
            assertFalse(context.containsBean(PaperExecutionConfiguration.DEFAULT_WORKER_BEAN_NAME));
            assertEquals(31, context.getBean(PaperTasks.class).async(() -> 31).join());
        }
    }
    @Test void sharedExecutorTimeoutCompletesOurResultWithoutInterruptingOrClosingTheBackend() throws Exception {
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",
                java.util.Map.of("spring.paper.lifecycle.shutdown-timeout-seconds", 0)));
        var backend = Executors.newSingleThreadExecutor();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var committed = new CompletableFuture<String>();
        var plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        try (var tasks = new PaperTasks(plugin, new PaperConfiguration(environment), backend)) {
            var result = tasks.async(() -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException error) { throw new AssertionError("Consumer worker was interrupted", error); }
                committed.complete("late commit"); return "late commit";
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            tasks.close();
            assertTrue(result.isCompletedExceptionally());
            assertFalse(backend.isShutdown());
            release.countDown();
            assertEquals("late commit", committed.get(2, TimeUnit.SECONDS));
            assertTrue(result.isCompletedExceptionally(), "I/O completion cannot replace the shutdown outcome");
        } finally { release.countDown(); backend.shutdownNow(); }
    }
    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.namespace()).thenReturn("workers");
        when(plugin.getDataFolder()).thenReturn(new File("build/test-data/workers"));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        PaperCoreConfiguration.registerHost(context, plugin, new PaperConfiguration(context.getEnvironment()));
        return context;
    }
}
