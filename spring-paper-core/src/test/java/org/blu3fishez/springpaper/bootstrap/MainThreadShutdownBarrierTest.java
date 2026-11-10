package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
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
import org.springframework.core.task.TaskRejectedException;

/** The main thread never delivers another tick while a synchronous shutdown hook waits for a save. */
@Timeout(10)
class MainThreadShutdownBarrierTest {
    private static final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
    private static FlushOwner lastOwner;

    @SpringPaperPluginApplication(name = "MainBarrier", configurations = Features.class)
    public static class Host extends JavaPlugin {}
    @SpringPaperPluginApplication(name = "FailedMainBarrier", configurations = FailingFeatures.class)
    public static class FailingHost extends JavaPlugin {}

    @Configuration(proxyBeanMethods = false)
    public static class Features {
        @Bean FlushOwner owner(PaperTasks tasks) { lastOwner = new FlushOwner(tasks, false); return lastOwner; }
        @Bean Infrastructure infrastructure(FlushOwner owner) { return new Infrastructure(owner); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class FailingFeatures {
        @Bean FlushOwner owner(PaperTasks tasks) { lastOwner = new FlushOwner(tasks, true); return lastOwner; }
        @Bean Infrastructure infrastructure(FlushOwner owner) { return new Infrastructure(owner); }
    }

    public static class FlushOwner {
        final PaperTasks tasks;
        final boolean failStartup;
        final AtomicInteger paperMutations = new AtomicInteger();
        final AtomicInteger inlineCleanups = new AtomicInteger();
        final AtomicInteger persisted = new AtomicInteger();
        final AtomicBoolean durableCommit = new AtomicBoolean();
        final CountDownLatch committing = new CountDownLatch(1);
        final CountDownLatch releaseCommit = new CountDownLatch(1);
        final CompletableFuture<String> source = new CompletableFuture<>();
        final CompletableFuture<Void> sourceProjection;
        CompletableFuture<?> lane = CompletableFuture.completedFuture(null);
        CompletableFuture<?> projection;
        boolean flushFinished;
        boolean destroyedAfterFlush;

        FlushOwner(PaperTasks tasks, boolean failStartup) {
            this.tasks = tasks;
            this.failStartup = failStartup;
            sourceProjection = tasks.nextTick(source, (value, error) -> paperMutations.incrementAndGet());
        }

        @OnPaperMainThreadStartup public void start() throws Exception {
            if (!failStartup) return;
            begin(false);
            assertNotNull(deliveries.poll(2, TimeUnit.SECONDS));
            throw new IllegalStateException("deliberate failure after worker queued a Paper projection");
        }

        void begin(boolean commitAfterProjection) {
            lane = tasks.async(() -> {
                projection = tasks.nextTick(() -> { paperMutations.incrementAndGet(); return "checked"; });
                projection.join();
                if (commitAfterProjection) {
                    committing.countDown();
                    try { assertTrue(releaseCommit.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError("accepted DB commit was interrupted", error); }
                    durableCommit.set(true);
                }
                return null;
            });
        }

        @OnPaperMainThreadShutdown public void flush() throws Exception {
            tasks.main(inlineCleanups::incrementAndGet).join();
            releaseCommit.countDown();
            // Same ordering as a final DB save queued behind an earlier player operation.
            lane.handle((ignored, error) -> null).thenCompose(ignored -> tasks.async(() -> {
                assertTrue(tasks.main(paperMutations::incrementAndGet).isCompletedExceptionally());
                return persisted.incrementAndGet();
            }))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(sourceProjection.isCompletedExceptionally(), "source-waiting UI must settle before user flush");
            assertFalse(source.isDone(), "UI shutdown must not cancel a durable source");
            assertTrue(tasks.nextTick(paperMutations::incrementAndGet).isCompletedExceptionally());
            flushFinished = true;
        }
    }

    public static class Infrastructure implements AutoCloseable {
        final FlushOwner owner;
        Infrastructure(FlushOwner owner) { this.owner = owner; }
        @Override public void close() { owner.destroyedAfterFlush = owner.flushFinished; }
    }

    @Test void settlesQueuedMainBeforeAUserFlushAndKeepsFinalDbAdmissionOpen() throws Exception {
        Host plugin = host(Host.class);
        var app = SpringPaperApplication.run(plugin);
        FlushOwner owner = lastOwner;
        owner.begin(false);
        Runnable stale = nextMain();
        // Paper JavaPlugin.setEnabled(false) clears this flag before invoking onDisable.
        when(plugin.isEnabled()).thenReturn(false);
        app.close();
        assertTrue(owner.flushFinished);
        assertTrue(owner.destroyedAfterFlush);
        assertEquals(1, owner.persisted.get());
        assertEquals(1, owner.inlineCleanups.get());
        assertFalse(owner.durableCommit.get());
        assertInstanceOf(TaskRejectedException.class, assertThrows(CompletionException.class, owner.projection::join).getCause());
        stale.run();
        owner.source.complete("durable source finished later");
        assertTrue(deliveries.isEmpty());
        assertEquals(0, owner.paperMutations.get());
        assertFalse(app.context().isActive());
    }

    @Test void acceptedDbCommitFinishesBeforeTheFollowingSaveAndInfrastructureDestruction() throws Exception {
        var app = SpringPaperApplication.run(host(Host.class));
        FlushOwner owner = lastOwner;
        owner.begin(true);
        nextMain().run();
        assertTrue(owner.committing.await(2, TimeUnit.SECONDS));
        app.close();
        assertTrue(owner.durableCommit.get());
        assertTrue(owner.flushFinished);
        assertTrue(owner.destroyedAfterFlush);
        assertEquals(1, owner.persisted.get());
        assertEquals(1, owner.paperMutations.get());
    }

    @Test void partialStartupUsesTheSameBarrierBeforeAttemptedOwnersFlush() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SpringPaperApplication.run(host(FailingHost.class)));
        assertEquals("deliberate failure after worker queued a Paper projection", failure.getCause().getCause().getMessage());
        assertEquals(0, failure.getCause().getSuppressed().length);
        assertTrue(lastOwner.flushFinished);
        assertTrue(lastOwner.destroyedAfterFlush);
        assertEquals(1, lastOwner.persisted.get());
        assertEquals(0, lastOwner.paperMutations.get());
    }

    private Runnable nextMain() throws InterruptedException {
        Runnable action = deliveries.poll(2, TimeUnit.SECONDS);
        assertNotNull(action, "expected a queued Paper callback");
        return action;
    }

    private <T extends JavaPlugin> T host(Class<T> type) {
        deliveries.clear();
        Thread main = Thread.currentThread();
        T plugin = mock(type);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("MainBarrier");
        when(plugin.namespace()).thenReturn("mainbarrier");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data/mainbarrier"));
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
