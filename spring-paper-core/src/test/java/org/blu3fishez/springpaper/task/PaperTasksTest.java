package org.blu3fishez.springpaper.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.StandardEnvironment;

class PaperTasksTest {
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
