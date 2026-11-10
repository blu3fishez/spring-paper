package org.blu3fishez.springpaper.task;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.Set;
import java.util.function.Supplier;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

/** Explicit worker/main-thread transitions; the consumer still validates player/session revisions. */
public final class PaperTasks implements AsyncTaskExecutor, AutoCloseable {
    private final JavaPlugin plugin;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final int shutdownSeconds;
    private final Set<CompletableFuture<Void>> pendingMain = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<CompletableFuture<Void>, BukkitTask> scheduled = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PaperTasks(JavaPlugin plugin, PaperConfiguration configuration) {
        this.plugin = plugin;
        shutdownSeconds = configuration.integer("spring.paper.lifecycle.shutdown-timeout-seconds", 5);
        if (shutdownSeconds < 0) throw new IllegalArgumentException("Shutdown timeout must be nonnegative");
    }
    public <T> CompletableFuture<T> async(Supplier<T> action) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Spring Paper is stopping"));
        try { return CompletableFuture.supplyAsync(action, this); }
        catch (RejectedExecutionException stopped) { return CompletableFuture.failedFuture(stopped); }
    }
    public CompletableFuture<Void> run(Runnable action) {
        return async(() -> { action.run(); return null; });
    }
    /** Standard executor bridge so integrations reuse the existing plugin-owned workers. */
    @Override public void execute(Runnable action) {
        if (closed) throw new TaskRejectedException("Spring Paper is stopping");
        try {
            workers.execute(() -> {
                Thread thread = Thread.currentThread();
                ClassLoader previous = thread.getContextClassLoader();
                thread.setContextClassLoader(plugin.getClass().getClassLoader());
                try { action.run(); } finally { thread.setContextClassLoader(previous); }
            });
        } catch (RejectedExecutionException stopped) {
            // AsyncTaskExecutor callers also receive Spring's rejection type in a shutdown race.
            throw new TaskRejectedException("Spring Paper worker rejected the task", stopped);
        }
    }
    public CompletableFuture<Void> main(Runnable action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        pendingMain.add(result);
        result.whenComplete((ignored, error) -> {
            pendingMain.remove(result);
            BukkitTask task = scheduled.remove(result);
            if (task != null) task.cancel();
        });
        Runnable guarded = () -> {
            if (result.isDone()) return;
            if (closed || !plugin.isEnabled()) { result.completeExceptionally(new IllegalStateException("Plugin is stopping")); return; }
            try { action.run(); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        };
        if (closed) { result.completeExceptionally(new IllegalStateException("Spring Paper is stopping")); return result; }
        try {
            if (plugin.getServer().isPrimaryThread()) guarded.run();
            else {
                BukkitTask task = plugin.getServer().getScheduler().runTask(plugin, guarded);
                scheduled.put(result, task);
                if (result.isDone() || closed) {
                    scheduled.remove(result, task);
                    task.cancel();
                    if (closed) result.completeExceptionally(new IllegalStateException("Spring Paper is stopping"));
                }
            }
        } catch (RuntimeException error) { result.completeExceptionally(error); }
        return result;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        // 메인 스레드가 이 close에서 워커를 기다리는 동안 queued main callback은 실행될 수 없다.
        // future를 미리 실패시켜 워커가 해당 callback 완료를 무한 대기하지 않게 한다.
        pendingMain.forEach(future -> future.completeExceptionally(new IllegalStateException("Spring Paper is stopping")));
        workers.shutdown();
        try {
            if (!workers.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                plugin.getLogger().warning("Spring Paper worker shutdown timed out; unfinished work was interrupted");
            }
        } catch (InterruptedException error) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
