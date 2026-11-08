package org.blu3fishez.springpaper.security.connection.fixture;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/** Deterministic deadlines: exercises scheduled callbacks without sleeps or wall-clock races. */
public final class ManualSecurityTime extends Clock implements TaskScheduler {
    private Instant now = Instant.parse("2026-11-08T00:00:00Z");
    private final ArrayList<Task> tasks = new ArrayList<>();
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    @Override public Clock getClock() { return this; }
    public void advance(Duration duration) {
        now = now.plus(duration);
        boolean ran;
        do {
            ran = false;
            for (Task task : new ArrayList<>(tasks)) {
                if (!task.isDone() && !now.isBefore(task.at)) { task.run(); ran = true; }
            }
        } while (ran);
    }
    @Override public ScheduledFuture<?> schedule(Runnable task, Instant at) {
        Task scheduled = new Task(task, at); tasks.add(scheduled); return scheduled;
    }
    @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) { throw new UnsupportedOperationException(); }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant start, Duration period) { throw new UnsupportedOperationException(); }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) { throw new UnsupportedOperationException(); }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) { throw new UnsupportedOperationException(); }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) { throw new UnsupportedOperationException(); }
    private final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
        final Instant at;
        Task(Runnable action, Instant at) { super(action, null); this.at = at; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(Duration.between(now, at).toNanos(), TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS)); }
    }
}
