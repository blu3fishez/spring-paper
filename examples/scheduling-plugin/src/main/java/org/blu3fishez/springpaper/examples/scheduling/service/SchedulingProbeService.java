package org.blu3fishez.springpaper.examples.scheduling.service;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.blu3fishez.springpaper.task.PaperMainThreadTaskScheduler;
import org.blu3fishez.springpaper.task.PaperTickScheduler;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public final class SchedulingProbeService {
    private final PaperThreadAccess threads;
    private final PaperTickScheduler ticks;
    private final TaskScheduler mainScheduler;
    private final Logger logger;
    private final AtomicInteger mainCalls = new AtomicInteger();
    private final AtomicInteger backgroundCalls = new AtomicInteger();
    private final AtomicInteger tickCalls = new AtomicInteger();
    private final AtomicInteger limitedCalls = new AtomicInteger();
    private final AtomicInteger cancelledCalls = new AtomicInteger();
    private volatile boolean started;
    private boolean delayed;
    private BukkitTask repeating;
    private BukkitTask limited;
    private ScheduledFuture<?> oneShot;

    public SchedulingProbeService(PaperThreadAccess threads, PaperTickScheduler ticks,
            @Qualifier(PaperMainThreadTaskScheduler.BEAN_NAME) TaskScheduler mainScheduler, Logger logger) {
        this.threads = threads; this.ticks = ticks; this.mainScheduler = mainScheduler; this.logger = logger;
    }
    @OnPaperMainThreadStartup public void start() {
        started = true;
        repeating = ticks.runTaskTimer(tickCalls::incrementAndGet, 1, 5);
        limited = ticks.runTaskTimer(new BukkitRunnable() {
            @Override public void run() { if (limitedCalls.incrementAndGet() == 3) cancel(); }
        }, 1, 2);
        oneShot = mainScheduler.schedule(() -> delayed = true,
                mainScheduler.getClock().instant().plus(Duration.ofMillis(250)));
        mainScheduler.schedule(cancelledCalls::incrementAndGet,
                mainScheduler.getClock().instant().plusSeconds(1)).cancel(false);
    }
    @Scheduled(scheduler = PaperMainThreadTaskScheduler.BEAN_NAME,
            initialDelayString = "PT0.1S", fixedDelayString = "PT0.2S")
    public void mainThreadWork() {
        if (!started) return;
        if (!threads.isPrimaryThread()) throw new IllegalStateException("Main job left the server thread");
        mainCalls.incrementAndGet();
    }
    @Scheduled(initialDelayString = "PT0.1S", fixedDelayString = "PT0.2S")
    public void backgroundWork() {
        if (!started) return;
        if (threads.isPrimaryThread()) throw new IllegalStateException("Background job entered the server thread");
        backgroundCalls.incrementAndGet();
    }
    public String verify() {
        if (!delayed || mainCalls.get() == 0 || backgroundCalls.get() == 0 || tickCalls.get() == 0
                || limitedCalls.get() != 3 || cancelledCalls.get() != 0) {
            throw new IllegalStateException("Probe is incomplete or failed; wait one second and retry");
        }
        return "SCHEDULING_OK main=" + mainCalls + " worker=" + backgroundCalls + " ticks=" + tickCalls
                + " selfCancel=" + limitedCalls + " cancelled=" + cancelledCalls;
    }
    @OnPaperMainThreadShutdown public void stop() {
        started = false;
        if (repeating != null) repeating.cancel();
        if (limited != null) limited.cancel();
        if (oneShot != null) oneShot.cancel(false);
        logger.info("SCHEDULING_STOPPED");
    }
}
