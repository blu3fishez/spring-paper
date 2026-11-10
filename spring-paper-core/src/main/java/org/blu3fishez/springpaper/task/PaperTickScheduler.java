package org.blu3fishez.springpaper.task;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.springframework.core.task.TaskRejectedException;

/** Plugin-owned Bukkit scheduling. Delays and periods are server ticks. */
public final class PaperTickScheduler implements java.util.concurrent.Executor, AutoCloseable {
    public static final String BEAN_NAME = "paperTickScheduler";
    private final JavaPlugin plugin;
    private final BukkitScheduler scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PaperTickScheduler(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.scheduler = plugin.getServer().getScheduler();
    }
    public boolean isActive() { return !closed.get() && plugin.isEnabled(); }
    /** Always defers to a server tick, including callers already on the Paper thread. */
    /** Fire-and-forget next tick. Use PaperTasks.nextTick when completion at stop must be observed. */
    @Override public void execute(Runnable action) { runTask(action); }
    public BukkitTask runTask(Runnable action) {
        requireActive();
        return scheduler.runTask(plugin, guarded(action));
    }
    public void runTask(Consumer<? super BukkitTask> action) {
        requireActive();
        scheduler.runTask(plugin, task -> { if (isActive()) action.accept(task); });
    }
    public BukkitTask runTaskLater(Runnable action, long delayTicks) {
        requireActive();
        return scheduler.runTaskLater(plugin, guarded(action), delayTicks);
    }
    public void runTaskLater(Consumer<? super BukkitTask> action, long delayTicks) {
        requireActive();
        scheduler.runTaskLater(plugin, task -> { if (isActive()) action.accept(task); }, delayTicks);
    }
    public BukkitTask runTaskTimer(Runnable action, long delayTicks, long periodTicks) {
        requireActive();
        return scheduler.runTaskTimer(plugin, guarded(action), delayTicks, periodTicks);
    }
    public void runTaskTimer(Consumer<? super BukkitTask> action, long delayTicks, long periodTicks) {
        requireActive();
        scheduler.runTaskTimer(plugin, task -> { if (isActive()) action.accept(task); }, delayTicks, periodTicks);
    }
    // Retain BukkitRunnable's native task identity so its own cancel() keeps working.
    public BukkitTask runTask(BukkitRunnable action) {
        requireActive();
        return action.runTask(plugin);
    }
    public BukkitTask runTaskLater(BukkitRunnable action, long delayTicks) {
        requireActive();
        return action.runTaskLater(plugin, delayTicks);
    }
    public BukkitTask runTaskTimer(BukkitRunnable action, long delayTicks, long periodTicks) {
        requireActive();
        return action.runTaskTimer(plugin, delayTicks, periodTicks);
    }
    private Runnable guarded(Runnable action) {
        Objects.requireNonNull(action);
        return () -> { if (isActive()) action.run(); };
    }
    private void requireActive() {
        if (!isActive()) throw new TaskRejectedException("Spring Paper tick scheduler is stopped");
    }
    @Override public void close() {
        // Stop remaining plugin-owned callbacks after feature flush hooks, before worker drain.
        // PaperTasks then resolves futures whose native callbacks were cancelled.
        if (closed.compareAndSet(false, true)) scheduler.cancelTasks(plugin);
    }
}
