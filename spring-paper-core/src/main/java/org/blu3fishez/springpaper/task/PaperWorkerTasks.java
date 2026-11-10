package org.blu3fishez.springpaper.task;

import java.util.Set;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;
import org.springframework.core.task.TaskRejectedException;

/** Admission/completion of this plugin's work, never a replacement pool or owner of a consumer executor. */
final class PaperWorkerTasks implements AutoCloseable {
    private final Executor executor;
    private final AutoCloseable ownedShutdown;
    private final ClassLoader loader;
    private final int shutdownSeconds;
    private final Logger logger;
    private final Set<Work> active = ConcurrentHashMap.newKeySet();
    private boolean closed;

    PaperWorkerTasks(Executor executor, AutoCloseable ownedShutdown, ClassLoader loader, int shutdownSeconds, Logger logger) {
        this.executor = Objects.requireNonNull(executor);
        this.ownedShutdown = ownedShutdown;
        this.loader = loader;
        this.shutdownSeconds = shutdownSeconds;
        this.logger = logger;
    }
    void execute(Runnable action) { dispatch(new Work(Objects.requireNonNull(action), null)); }
    <T> CompletableFuture<T> submit(Callable<T> action) {
        Objects.requireNonNull(action);
        var result = new CompletableFuture<T>();
        var work = new Work(() -> {
            try { result.complete(action.call()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        }, result);
        dispatch(work);
        return result;
    }
    private void dispatch(Work work) {
        synchronized (this) {
            if (closed) throw rejected();
            active.add(work);
        }
        work.ended.whenComplete((ignored, error) -> active.remove(work));
        try { executor.execute(work); }
        catch (RuntimeException | Error failure) {
            work.abandon(failure);
            if (failure instanceof RejectedExecutionException rejected)
                throw new TaskRejectedException(executor, work, rejected);
            throw failure;
        }
    }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        active.forEach(work -> work.abandonIfWaiting(rejected()));
        try {
            if (ownedShutdown != null) {
                ownedShutdown.close(); // Spring's default executor implements the wait/interrupt policy.
                if (!active.isEmpty() && logger != null)
                    logger.warning("Spring Paper workers remain after shutdown interruption; unfinished I/O may still commit");
            }
            else CompletableFuture.allOf(active.stream().map(work -> work.ended).toArray(CompletableFuture[]::new))
                    .get(shutdownSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            if (logger != null) logger.warning("Spring Paper worker drain timed out; the consumer still owns its executor and unfinished I/O");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException impossible) { throw new IllegalStateException(impossible); }
        catch (Exception failure) { throw new IllegalStateException("Spring Paper worker shutdown failed", failure); }
        finally {
            // Completion is separate from I/O termination: a timed-out operation may still commit.
            // Do not close/interrupt a shared consumer executor to make its result look finished.
            active.forEach(work -> { if (work.result != null) work.result.completeExceptionally(rejected()); });
        }
    }
    private static TaskRejectedException rejected() { return new TaskRejectedException("Spring Paper workers are stopping"); }

    private final class Work implements Runnable {
        final Runnable action;
        final CompletableFuture<?> result;
        final CompletableFuture<Void> ended = new CompletableFuture<>();
        boolean started;
        boolean abandoned;
        Work(Runnable action, CompletableFuture<?> result) { this.action = action; this.result = result; }
        @Override public void run() {
            synchronized (this) {
                if (abandoned || result != null && result.isDone()) { ended.complete(null); return; }
                started = true;
            }
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try { action.run(); }
            finally { thread.setContextClassLoader(previous); ended.complete(null); }
        }
        synchronized void abandonIfWaiting(Throwable failure) { if (!started) abandon(failure); }
        synchronized void abandon(Throwable failure) {
            abandoned = true;
            if (result != null) result.completeExceptionally(failure);
            ended.complete(null);
        }
    }
}
