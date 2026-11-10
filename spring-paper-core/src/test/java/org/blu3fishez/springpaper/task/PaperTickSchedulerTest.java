package org.blu3fishez.springpaper.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;

class PaperTickSchedulerTest {
    @Test void executorAlwaysDefersAndForwardsTheOriginalTickDurations() {
        var host = new Host();
        var calls = new AtomicInteger();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        when(host.nativeScheduler.runTask(eq(host.plugin), callback.capture())).thenReturn(host.task);
        try (var ticks = new PaperTickScheduler(host.plugin)) {
            ticks.execute(calls::incrementAndGet);
            assertEquals(0, calls.get());
            callback.getValue().run();
            assertEquals(1, calls.get());
            assertSame(host.task, ticks.runTaskLater(calls::incrementAndGet, 7));
            assertSame(host.task, ticks.runTaskTimer(calls::incrementAndGet, 3, 19));
            verify(host.nativeScheduler).runTaskLater(eq(host.plugin), any(Runnable.class), eq(7L));
            verify(host.nativeScheduler).runTaskTimer(eq(host.plugin), any(Runnable.class), eq(3L), eq(19L));
        }
    }

    @Test void closeCancelsThisPluginsCallbacksAndFencesAlreadyDeliveredWork() {
        var host = new Host();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        when(host.nativeScheduler.runTask(eq(host.plugin), callback.capture())).thenReturn(host.task);
        var calls = new AtomicInteger();
        var ticks = new PaperTickScheduler(host.plugin);
        ticks.execute(calls::incrementAndGet);
        ticks.close(); ticks.close();
        callback.getValue().run();
        assertEquals(0, calls.get());
        assertFalse(ticks.isActive());
        verify(host.nativeScheduler).cancelTasks(host.plugin);
        assertThrows(TaskRejectedException.class, () -> ticks.execute(calls::incrementAndGet));
    }

    @Test void nativeBukkitRunnableKeepsItsOwnCancellationIdentity() {
        var host = new Host();
        try (var bukkit = mockStatic(Bukkit.class); var ticks = new PaperTickScheduler(host.plugin)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(host.nativeScheduler);
            var runnable = new BukkitRunnable() { @Override public void run() { cancel(); } };
            assertSame(host.task, ticks.runTaskTimer(runnable, 0, 5));
            runnable.run();
            verify(host.nativeScheduler).cancelTask(42);
            verify(host.nativeScheduler).runTaskTimer(host.plugin, (Runnable) runnable, 0, 5);
        }
    }

    private static final class Host {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final BukkitScheduler nativeScheduler = mock(BukkitScheduler.class);
        final BukkitTask task = mock(BukkitTask.class);
        Host() {
            var server = mock(Server.class);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.isEnabled()).thenReturn(true);
            when(task.getTaskId()).thenReturn(42);
            when(server.getScheduler()).thenReturn(nativeScheduler);
            when(nativeScheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenReturn(task);
            when(nativeScheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(task);
        }
    }
}
