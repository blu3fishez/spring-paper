package org.blu3fishez.springpaper.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.task.TaskRejectedException;

class ManagedMainExecutionTest {
    @Test void rawExecutorFencesTheRunnableButCannotCompleteACallerOwnedContinuation() {
        var host = new Host();
        try (var ticks = new PaperTickScheduler(host.plugin)) {
            var mutations = new AtomicInteger();
            var callerOwned = CompletableFuture.completedFuture("loaded")
                    .thenAcceptAsync(value -> mutations.incrementAndGet(), ticks);
            ticks.close();
            host.deliveries.remove().run();
            assertFalse(callerOwned.isDone(), "Executor.execute does not expose the caller's completion object");
            assertEquals(0, mutations.get());
            callerOwned.cancel(false);
        }
    }
    @Test void immediateMainAndNextTickRetainTheirDifferentTimingEvenOnMain() {
        var host = new Host();
        when(host.server.isPrimaryThread()).thenReturn(true);
        try (var tasks = host.tasks()) {
            var count = new AtomicInteger();
            assertTrue(tasks.main(count::incrementAndGet).isDone());
            var next = tasks.nextTick(count::incrementAndGet);
            assertEquals(1, count.get());
            assertFalse(next.isDone());
            host.deliveries.remove().run();
            next.join();
            assertEquals(2, count.get());
        }
    }
    @Test void closeCompletesAManagedContinuationWaitingForItsSourceWithoutCancellingTheSource() {
        var host = new Host();
        var source = new CompletableFuture<String>();
        var mutations = new AtomicInteger();
        var tasks = host.tasks();
        var result = tasks.nextTick(source, (value, error) -> mutations.incrementAndGet());
        tasks.close();
        assertFalse(source.isDone(), "a UI continuation does not own the DB operation");
        assertInstanceOf(TaskRejectedException.class, assertThrows(CompletionException.class, result::join).getCause());
        source.complete("committed after UI closed");
        assertTrue(host.deliveries.isEmpty());
        assertEquals(0, mutations.get());
    }
    @Test void cancellationBeforeSourceOrBeforeNativeDeliveryFencesBothAndNeverInterruptsMain() {
        var host = new Host();
        try (var tasks = host.tasks()) {
            var source = new CompletableFuture<String>();
            var count = new AtomicInteger();
            var waiting = tasks.nextTick(source, (value, error) -> count.incrementAndGet());
            assertTrue(waiting.cancel(true));
            source.complete("read");
            assertTrue(host.deliveries.isEmpty());
            var queued = tasks.nextTick(count::incrementAndGet);
            assertTrue(queued.cancel(true));
            host.deliveries.remove().run();
            assertTrue(queued.isCancelled());
            assertEquals(0, count.get());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
    @Test void stopCompletesQueuedResultsAndLateDeliveryCannotMutatePaper() {
        var host = new Host();
        var tasks = host.tasks();
        var count = new AtomicInteger();
        var result = tasks.nextTick(CompletableFuture.completedFuture("read"), (value, error) -> count.incrementAndGet());
        tasks.close();
        assertTrue(result.isCompletedExceptionally());
        host.deliveries.remove().run();
        assertEquals(0, count.get());
        assertTrue(tasks.nextTick(count::incrementAndGet).isCompletedExceptionally());
    }
    @Test void sourceAndHandlerFailuresHaveExplicitOutcomesAndTheLoaderIsRestored() {
        var host = new Host();
        try (var tasks = host.tasks()) {
            Throwable failure = new IllegalArgumentException("read failed");
            var previous = Thread.currentThread().getContextClassLoader();
            var result = tasks.nextTick(CompletableFuture.failedFuture(failure), (value, error) -> {
                assertSame(failure, error);
                assertSame(host.plugin.getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
                throw new IllegalStateException("apply failed");
            });
            host.deliveries.remove().run();
            assertSame(previous, Thread.currentThread().getContextClassLoader());
            assertEquals("apply failed", assertThrows(CompletionException.class, result::join).getCause().getMessage());
        }
    }
    @Test void closeReleasesAWorkerWaitingForManagedMainCompletion() throws Exception {
        var host = new Host();
        var tasks = host.tasks();
        var queued = new java.util.concurrent.CountDownLatch(1);
        when(host.scheduler.runTask(eq(host.plugin), any(Runnable.class))).thenAnswer(call -> {
            host.deliveries.add(call.getArgument(1)); queued.countDown(); return mock(BukkitTask.class);
        });
        var worker = tasks.async(() -> tasks.nextTick(() -> fail("closed callback ran")).join());
        assertTrue(queued.await(2, TimeUnit.SECONDS));
        assertTimeout(java.time.Duration.ofSeconds(2), tasks::close);
        assertTrue(worker.isCompletedExceptionally());
        host.deliveries.remove().run();
    }
    @Test void closeBetweenNativeSubmissionAndHandlePublicationStillCancelsAndFencesDelivery() {
        var host = new Host();
        var tasks = host.tasks();
        var nativeTask = mock(BukkitTask.class);
        when(host.scheduler.runTask(eq(host.plugin), any(Runnable.class))).thenAnswer(call -> {
            host.deliveries.add(call.getArgument(1));
            tasks.close();
            return nativeTask;
        });
        var result = tasks.nextTick(() -> fail("late native task ran"));
        assertTrue(result.isCompletedExceptionally());
        verify(nativeTask).cancel();
        host.deliveries.remove().run();
    }
    @Test void typedNextTickCompletesWithTheActualPaperValue() {
        var host = new Host();
        try (var tasks = host.tasks()) {
            var result = tasks.nextTick(() -> "converted item");
            assertFalse(result.isDone());
            host.deliveries.remove().run();
            assertEquals("converted item", result.join());
        }
    }
    @Test void aReentrantMainQuiesceSettlesQueuedWorkAndPreservesCurrentInlineCleanup() {
        var host = new Host();
        Thread main = Thread.currentThread();
        when(host.server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == main);
        try (var tasks = host.tasks()) {
            var mutations = new AtomicInteger();
            var queued = tasks.nextTick(mutations::incrementAndGet);
            var current = tasks.main(() -> {
                tasks.quiesceMainThread();
                tasks.main(mutations::incrementAndGet).join();
            });
            current.join();
            assertTrue(queued.isCompletedExceptionally());
            host.deliveries.remove().run();
            assertEquals(1, mutations.get());
        }
    }
    @Test void anOffThreadQuiesceIsRejectedWithoutClosingMainAdmission() throws Exception {
        var host = new Host();
        Thread main = Thread.currentThread();
        when(host.server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == main);
        try (var tasks = host.tasks()) {
            var attempted = tasks.async(() -> { tasks.quiesceMainThread(); return null; });
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> attempted.get(2, TimeUnit.SECONDS)).getCause());
            var pending = tasks.nextTick(() -> "still active");
            host.deliveries.remove().run();
            assertEquals("still active", pending.join());
        }
    }
    @Test void aDisabledHostAllowsInlineCleanupOnlyWithinExplicitMainQuiesce() {
        var host = new Host();
        when(host.server.isPrimaryThread()).thenReturn(true);
        when(host.plugin.isEnabled()).thenReturn(false);
        var tasks = host.tasks();
        var mutations = new AtomicInteger();
        assertTrue(tasks.main(mutations::incrementAndGet).isCompletedExceptionally());
        tasks.quiesceMainThread();
        tasks.main(mutations::incrementAndGet).join();
        assertTrue(tasks.nextTick(mutations::incrementAndGet).isCompletedExceptionally());
        tasks.close();
        assertTrue(tasks.main(mutations::incrementAndGet).isCompletedExceptionally());
        assertEquals(1, mutations.get());
    }
    private static class Host {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Queue<Runnable> deliveries = new ArrayDeque<>();
        Host() {
            when(plugin.getServer()).thenReturn(server);
            when(plugin.isEnabled()).thenReturn(true);
            when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                deliveries.add(call.getArgument(1)); return mock(BukkitTask.class);
            });
        }
        PaperTasks tasks() { return new PaperTasks(plugin, new PaperConfiguration(new StandardEnvironment())); }
    }
}
