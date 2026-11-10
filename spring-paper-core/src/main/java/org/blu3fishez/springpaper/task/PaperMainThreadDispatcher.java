package org.blu3fishez.springpaper.task;

import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.concurrent.Callable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.springframework.core.task.TaskRejectedException;

/** Owns only futures returned by this module, including continuations still waiting for their source. */
final class PaperMainThreadDispatcher implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Set<Call<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean quiesced;
    private volatile boolean closed;

    PaperMainThreadDispatcher(JavaPlugin plugin) { this.plugin = Objects.requireNonNull(plugin); }

    <T> CompletableFuture<T> submit(Callable<T> action, boolean defer) {
        Objects.requireNonNull(action);
        boolean inline = !defer && plugin.getServer().isPrimaryThread();
        Call<T> call = register(inline);
        dispatch(call, action, inline);
        return call.result;
    }
    <T> CompletableFuture<Void> submit(CompletionStage<T> source,
            BiConsumer<? super T, ? super Throwable> action, boolean defer) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(action);
        Call<Void> call = register(false);
        if (!call.result.isDone()) {
            try { source.whenComplete((value, error) -> dispatch(call,
                    () -> { action.accept(value, error); return null; }, !defer && plugin.getServer().isPrimaryThread())); }
            catch (Throwable error) { call.result.completeExceptionally(error); }
        }
        return call.result;
    }
    private <T> Call<T> register(boolean inline) {
        var call = new Call<T>();
        pending.add(call);
        call.result.whenComplete((ignored, error) -> {
            pending.remove(call);
            BukkitTask nativeTask = call.nativeTask;
            if (nativeTask != null) nativeTask.cancel();
        });
        if (closed || quiesced && !inline) call.reject();
        return call;
    }

    private <T> void dispatch(Call<T> call, Callable<T> action, boolean inline) {
        if (call.result.isDone()) return;
        if (closed || quiesced && !inline) { call.reject(); return; }
        Runnable guarded = () -> {
            if (call.result.isDone()) return;
            if (closed || quiesced && !inline || !plugin.isEnabled()) { call.reject(); return; }
            call.started = true;
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(plugin.getClass().getClassLoader());
            try { call.result.complete(action.call()); }
            catch (Throwable error) { call.result.completeExceptionally(error); }
            finally { thread.setContextClassLoader(previous); }
        };
        try {
            if (inline) guarded.run();
            else {
                BukkitTask nativeTask = plugin.getServer().getScheduler().runTask(plugin, guarded);
                call.nativeTask = nativeTask;
                // Cancellation/close may win between native submission and publishing its handle.
                if (closed || quiesced) call.reject();
                if (call.result.isDone()) nativeTask.cancel();
            }
        } catch (Throwable error) { call.result.completeExceptionally(error); }
    }

    void quiesce() {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Quiesce Spring Paper on the server thread");
        quiesced = true;
        // A reentrant barrier must not fail the main action that is already executing it.
        pending.forEach(call -> { if (!call.started) call.reject(); });
    }

    @Override public void close() {
        closed = true;
        // Never execute user callbacks to release their futures. The upstream operation remains owned
        // by its caller: cancelling a UI continuation must not cancel a shared/committing DB operation.
        pending.forEach(Call::reject);
    }

    private static final class Call<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        volatile boolean started;
        volatile BukkitTask nativeTask;
        void reject() { result.completeExceptionally(new TaskRejectedException("Spring Paper main execution is stopping")); }
    }
}
