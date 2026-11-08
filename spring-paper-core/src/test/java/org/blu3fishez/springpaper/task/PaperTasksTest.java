package org.blu3fishez.springpaper.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.StandardEnvironment;

class PaperTasksTest {
    @Test void standardExecutorReusesPluginWorkerContextAndRejectsAfterStop() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        var previous = Thread.currentThread().getContextClassLoader();
        var foreign = new ClassLoader(null) {};
        PaperTasks tasks = new PaperTasks(plugin, new PaperConfiguration(new StandardEnvironment()));
        try {
            Thread.currentThread().setContextClassLoader(foreign);
            var loader = CompletableFuture.supplyAsync(() -> Thread.currentThread().getContextClassLoader(), tasks);
            assertSame(plugin.getClass().getClassLoader(), loader.get(5, TimeUnit.SECONDS));
            assertSame(foreign, Thread.currentThread().getContextClassLoader());
            tasks.close();
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> tasks.execute(() -> fail("must not run")));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            tasks.close();
        }
    }
    @Test void waitsForRunningWorkAndRejectsNewWorkDuringShutdown() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        PaperTasks tasks = new PaperTasks(plugin, new PaperConfiguration(new StandardEnvironment()));
        CountDownLatch working = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        var result = tasks.async(() -> {
            working.countDown();
            try { finish.await(); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
            }
            return "finished";
        });
        assertTrue(working.await(5, TimeUnit.SECONDS));
        CountDownLatch closing = new CountDownLatch(1);
        var stopped = CompletableFuture.runAsync(() -> { closing.countDown(); tasks.close(); });
        try {
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> stopped.get(100, TimeUnit.MILLISECONDS));
        } finally { finish.countDown(); }
        stopped.get(5, TimeUnit.SECONDS);
        assertEquals("finished", result.get(5, TimeUnit.SECONDS));
        assertTrue(tasks.async(() -> "must-not-start").isCompletedExceptionally());
    }
    @Test void stopCancelsQueuedCallbacksAndCompletesTheirFutures() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask scheduled = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.isEnabled()).thenReturn(true);
        var callback = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTask(eq(plugin), callback.capture())).thenReturn(scheduled);
        AtomicInteger calls = new AtomicInteger();
        PaperTasks tasks = new PaperTasks(plugin, new PaperConfiguration(new StandardEnvironment()));
        var result = tasks.main(calls::incrementAndGet);
        assertFalse(result.isDone());
        tasks.close();
        assertTrue(result.isCompletedExceptionally());
        verify(scheduled).cancel();
        callback.getValue().run();
        assertEquals(0, calls.get());
    }
}
