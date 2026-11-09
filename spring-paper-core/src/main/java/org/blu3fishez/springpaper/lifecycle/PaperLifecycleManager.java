package org.blu3fishez.springpaper.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;

/** Main-thread activation and cleanup of attempted owners; discovery and ordering live in the plan. */
public final class PaperLifecycleManager implements AutoCloseable {
    private enum State { NEW, STARTING, RUNNING, CLOSED }
    private final PaperLifecyclePlan plan;
    private final List<Integer> attempted = new ArrayList<>();
    private State state = State.NEW;

    public PaperLifecycleManager(ApplicationContext context, Map<String, Class<?>> candidates) {
        plan = new PaperLifecyclePlan(context, candidates);
    }

    public void start() {
        if (state == State.RUNNING) return;
        if (state != State.NEW) throw new IllegalStateException("Paper lifecycle cannot restart after failure or close");
        state = State.STARTING;
        for (int position : plan.startup()) {
            // Record before invocation: the failing owner must clean resources acquired by its partial start.
            // A stop-only owner is enrolled internally, without forcing an empty public start method.
            attempted.add(position);
            plan.hook(position).startOwner();
        }
        state = State.RUNNING;
    }

    @Override public void close() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        RuntimeException failure = null;
        for (int position : plan.shutdown(attempted)) {
            try { plan.hook(position).stopOwner(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        attempted.clear();
        if (failure != null) throw failure;
    }
}
