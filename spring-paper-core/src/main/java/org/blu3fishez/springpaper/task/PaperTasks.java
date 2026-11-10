package org.blu3fishez.springpaper.task;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.core.task.AsyncTaskExecutor;

/** Plugin-owned completion/admission; the backend executor and session validity have separate owners. */
public final class PaperTasks implements AsyncTaskExecutor, AutoCloseable {
    private final PaperWorkerTasks workers;
    private final PaperMainThreadDispatcher main;

    /** Standalone compatibility. Spring consumers inject the bean to reuse their configured worker. */
    @Deprecated(forRemoval = false)
    public PaperTasks(JavaPlugin plugin, PaperConfiguration configuration) {
        this(plugin, configuration, PaperExecutionConfiguration.standaloneWorker(configuration));
    }
    private PaperTasks(JavaPlugin plugin, PaperConfiguration configuration,
            org.springframework.core.task.SimpleAsyncTaskExecutor owned) {
        this(plugin, configuration, owned, owned::close);
    }
    /** Borrows the executor; closing this facade drains its own work without closing that executor. */
    public PaperTasks(JavaPlugin plugin, PaperConfiguration configuration, Executor worker) {
        this(plugin, configuration, worker, null);
    }
    PaperTasks(JavaPlugin plugin, PaperConfiguration configuration, Executor worker, AutoCloseable ownedShutdown) {
        int seconds = configuration.integer("spring.paper.lifecycle.shutdown-timeout-seconds", 5);
        if (seconds < 0) throw new IllegalArgumentException("Shutdown timeout must be nonnegative");
        workers = new PaperWorkerTasks(worker, ownedShutdown, plugin.getClass().getClassLoader(), seconds, plugin.getLogger());
        main = new PaperMainThreadDispatcher(plugin);
    }
    public <T> CompletableFuture<T> async(Supplier<T> action) {
        try { return workers.submit(action::get); }
        catch (RejectedExecutionException stopped) { return CompletableFuture.failedFuture(stopped); }
    }
    public CompletableFuture<Void> run(Runnable action) { return async(() -> { action.run(); return null; }); }
    /** Raw Executor path: ownership of a caller-created CompletableFuture cannot be inferred. */
    @Override public void execute(Runnable action) { workers.execute(action); }
    @Override public CompletableFuture<Void> submitCompletable(Runnable action) { return workers.submit(() -> { action.run(); return null; }); }
    @Override public <T> CompletableFuture<T> submitCompletable(Callable<T> action) { return workers.submit(action); }
    @Override public Future<?> submit(Runnable action) { return submitCompletable(action); }
    @Override public <T> Future<T> submit(Callable<T> action) { return submitCompletable(action); }

    /** Runs immediately when already on main, otherwise queues a main callback. */
    public CompletableFuture<Void> main(Runnable action) { return main.submit(() -> { action.run(); return null; }, false); }
    /** Always defers to the next tick, including callers already on main. */
    public CompletableFuture<Void> nextTick(Runnable action) { return main.submit(() -> { action.run(); return null; }, true); }
    /** Next-tick call with a managed value/failure, e.g. a Paper item conversion awaited by a worker. */
    public <T> CompletableFuture<T> nextTick(Callable<T> action) { return main.submit(action, true); }
    /** Handles the outcome next tick. Cancelling this UI result never cancels the source operation. */
    public <T> CompletableFuture<Void> nextTick(CompletionStage<T> source,
            BiConsumer<? super T, ? super Throwable> handler) { return main.submit(source, handler, true); }

    /**
     * Infrastructure shutdown barrier, called on Paper main before consumer flush hooks.
     * Settles queued/source-waiting main work and rejects future deferred dispatch, without cancelling
     * source operations or closing worker admission. Inline {@link #main(Runnable)} cleanup still runs
     * on main; {@link #close()} later drains workers and rejects all main execution.
     */
    public void quiesceMainThread() { main.quiesce(); }

    @Override public void close() {
        main.close(); // Release workers waiting for main before draining any backend.
        workers.close();
    }
}
