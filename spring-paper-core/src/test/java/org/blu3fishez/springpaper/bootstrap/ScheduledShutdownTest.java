package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class ScheduledShutdownTest {
    static Job lastJob;
    static final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();

    @SpringPaperPluginApplication(name = "Scheduled", configurations = Features.class)
    public static class Host extends JavaPlugin {}
    @SpringPaperPluginApplication(name = "FailedScheduled", configurations = FailingFeatures.class)
    public static class FailingHost extends JavaPlugin {}
    @Configuration(proxyBeanMethods = false) @EnableScheduling
    public static class Features {
        @Bean Job job(PaperTasks tasks) { lastJob = new Job(tasks); return lastJob; }
    }
    @Configuration(proxyBeanMethods = false) @EnableScheduling
    public static class FailingFeatures {
        @Bean Job job(PaperTasks tasks) { lastJob = new Job(tasks); return lastJob; }
        @Bean Broken broken() { return new Broken(); }
    }
    public static class Job implements AutoCloseable {
        final PaperTasks tasks;
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger mutations = new AtomicInteger();
        boolean destroyedAfterCompletion;
        Job(PaperTasks tasks) { this.tasks = tasks; }
        @Scheduled(fixedDelayString = "PT1H")
        public void run() {
            try { tasks.main(mutations::incrementAndGet).join(); }
            finally { finished.countDown(); }
        }
        @Override public void close() { destroyedAfterCompletion = finished.getCount() == 0; }
    }
    public static class Broken {
        @OnPaperMainThreadStartup public void start() throws InterruptedException {
            assertNotNull(deliveries.poll(5, TimeUnit.SECONDS));
            throw new IllegalStateException("deliberate failure after scheduled submission");
        }
    }
    @Test @Timeout(10)
    void closingOnMainUnblocksTheScheduledWorkerBeforeSpringDestroysResources() throws Exception {
        var app = SpringPaperApplication.run(host(Host.class));
        Runnable stale = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(stale);
        assertTimeout(Duration.ofSeconds(2), app::close);
        stale.run();
        assertTrue(lastJob.finished.await(1, TimeUnit.SECONDS));
        assertTrue(lastJob.destroyedAfterCompletion);
        assertEquals(0, lastJob.mutations.get());
        assertFalse(app.context().isActive());
    }
    @Test @Timeout(10)
    void partialStartupAlsoCancelsScheduledProducersAndPendingMainWork() throws Exception {
        assertThrows(IllegalStateException.class, () -> SpringPaperApplication.run(host(FailingHost.class)));
        assertTrue(lastJob.finished.await(1, TimeUnit.SECONDS));
        assertTrue(lastJob.destroyedAfterCompletion);
        assertEquals(0, lastJob.mutations.get());
    }
    private <T extends JavaPlugin> T host(Class<T> type) {
        deliveries.clear();
        Thread main = Thread.currentThread();
        T plugin = mock(type);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Scheduled");
        when(plugin.namespace()).thenReturn("scheduled");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data/scheduled"));
        when(plugin.getResource(anyString())).thenAnswer(call -> new ByteArrayInputStream(new byte[0]));
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == main);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            deliveries.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        return plugin;
    }
}
