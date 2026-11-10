package org.blu3fishez.springpaper.task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ReflectionUtils;

/**
 * Wall-clock scheduling with task bodies on the Paper main thread.
 * Select explicitly with {@code @Scheduled(scheduler = PaperMainThreadTaskScheduler.BEAN_NAME)}.
 * Tasks must not block the server thread; tick-based game mechanics still belong to Bukkit.
 */
public final class PaperMainThreadTaskScheduler implements TaskScheduler, BeanClassLoaderAware,
        DisposableBean, AutoCloseable {
    public static final String BEAN_NAME = "paperMainThreadScheduler";
    private final PaperTasks tasks;
    private final ThreadPoolTaskScheduler timer = new ThreadPoolTaskScheduler();
    private final Set<Invocation> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ClassLoader classLoader = PaperMainThreadTaskScheduler.class.getClassLoader();

    public PaperMainThreadTaskScheduler(PaperTasks tasks) {
        this.tasks = Objects.requireNonNull(tasks);
        timer.setThreadNamePrefix("spring-paper-main-scheduler-");
        timer.setDaemon(true);
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        timer.initialize();
    }

    @Override public void setBeanClassLoader(ClassLoader classLoader) { this.classLoader = classLoader; }
    @Override public Clock getClock() { return timer.getClock(); }

    @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return submit(task, invocation -> timer.schedule(invocation, trigger));
    }
    @Override public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return submit(task, invocation -> timer.schedule(invocation, startTime));
    }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return submit(task, invocation -> timer.scheduleAtFixedRate(invocation, startTime, period));
    }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return submit(task, invocation -> timer.scheduleAtFixedRate(invocation, period));
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return submit(task, invocation -> timer.scheduleWithFixedDelay(invocation, startTime, delay));
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return submit(task, invocation -> timer.scheduleWithFixedDelay(invocation, delay));
    }

    private ScheduledFuture<?> submit(Runnable task, Function<Runnable, ScheduledFuture<?>> schedule) {
        if (closed.get()) throw new TaskRejectedException("Spring Paper main-thread scheduler is stopped");
        var invocation = new Invocation(Objects.requireNonNull(task));
        ScheduledFuture<?> future = schedule.apply(invocation);
        if (future == null) return null; // A Spring Trigger may have no next execution.
        invocation.bind(future);
        return invocation;
    }

    @Override public void destroy() { close(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // The server thread may be closing us while the timer waits for a queued Paper callback.
        // Cancel that callback before interrupting/draining timer work; never wait on the server thread.
        active.forEach(invocation -> invocation.cancel(false));
        timer.shutdown();
    }

    private final class Invocation implements Runnable, ScheduledFuture<Object> {
        private final Runnable action;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;
        private volatile CompletableFuture<Void> completion;

        private Invocation(Runnable action) { this.action = action; }

        private void bind(ScheduledFuture<?> future) {
            this.future = future;
            if (cancelled.get() || closed.get()) cancel(false);
        }

        @Override public void run() {
            if (cancelled.get() || closed.get()) return;
            active.add(this);
            try {
                if (cancelled.get() || closed.get()) return;
                CompletableFuture<Void> pending = tasks.main(() -> {
                    if (cancelled.get() || closed.get()) return;
                    Thread thread = Thread.currentThread();
                    ClassLoader previous = thread.getContextClassLoader();
                    thread.setContextClassLoader(classLoader);
                    try { action.run(); } finally { thread.setContextClassLoader(previous); }
                });
                completion = pending;
                if (cancelled.get() || closed.get()) pending.cancel(false);
                // A fire-and-forget handoff would start fixedDelay before the Paper action finishes
                // and enqueue another callback each period while the server is stalled.
                pending.get();
            } catch (InterruptedException interrupted) {
                CompletableFuture<Void> pending = completion;
                if (pending != null) pending.cancel(false);
                Thread.currentThread().interrupt();
                if (!cancelled.get() && !closed.get()) {
                    throw new IllegalStateException("Paper scheduled dispatch was interrupted", interrupted);
                }
            } catch (CancellationException cancelledTask) {
                if (!cancelled.get() && !closed.get()) throw cancelledTask;
            } catch (ExecutionException failure) {
                if (!cancelled.get() && !closed.get()) ReflectionUtils.rethrowRuntimeException(failure.getCause());
            } finally {
                completion = null;
                active.remove(this);
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            ScheduledFuture<?> scheduled = future;
            boolean changed = scheduled == null ? cancelled.compareAndSet(false, true)
                    : scheduled.cancel(mayInterruptIfRunning);
            if (changed || cancelled.get()) {
                cancelled.set(true);
                CompletableFuture<Void> pending = completion;
                if (pending != null) pending.cancel(false);
            }
            return changed;
        }

        private ScheduledFuture<?> future() { return Objects.requireNonNull(future, "Task is not bound"); }
        @Override public boolean isCancelled() { return future().isCancelled(); }
        @Override public boolean isDone() { return future().isDone(); }
        @Override public Object get() throws InterruptedException, ExecutionException { return future().get(); }
        @Override public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException { return future().get(timeout, unit); }
        @Override public long getDelay(TimeUnit unit) { return future().getDelay(unit); }
        @Override public int compareTo(Delayed other) { return future().compareTo(other); }
    }
}
