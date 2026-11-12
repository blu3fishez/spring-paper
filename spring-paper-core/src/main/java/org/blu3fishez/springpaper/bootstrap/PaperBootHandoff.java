package org.blu3fishez.springpaper.bootstrap;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Only the bootstrap's fixed initializer/activation/cleanup calls cross this internal seam. */
final class PaperBootHandoff {
    private final Thread mainThread = Thread.currentThread();
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private volatile Thread preparationThread;

    void checkCloseAllowed() {
        Thread current = Thread.currentThread();
        if (current != mainThread && current != preparationThread) {
            throw new IllegalStateException("Stop Spring Paper on the server thread");
        }
    }

    void onMain(Runnable action) {
        if (Thread.currentThread() == mainThread) { action.run(); return; }
        checkCloseAllowed();
        var completion = new CompletableFuture<Void>();
        requests.add(new Request(action, completion));
        try { completion.join(); }
        catch (CompletionException failure) {
            if (failure.getCause() instanceof Error fatal) throw fatal;
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    <T> T prepare(Callable<T> action) throws InterruptedException, ExecutionException {
        FutureTask<T> result = new FutureTask<>(action);
        Thread preparation = Thread.ofVirtual().name("spring-paper-bootstrap").unstarted(result);
        preparationThread = preparation;
        preparation.start();
        boolean interrupted = false;
        try {
            while (!result.isDone()) {
                Request request;
                try { request = requests.poll(25, TimeUnit.MILLISECONDS); }
                catch (InterruptedException error) { interrupted = true; continue; }
                if (request == null) continue;
                try { request.action.run(); request.completion.complete(null); }
                catch (Throwable failure) { request.completion.completeExceptionally(failure); }
            }
            T value = result.get();
            if (interrupted) throw new InterruptedException("Interrupted during Spring Paper startup");
            return value;
        } finally {
            preparationThread = null;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record Request(Runnable action, CompletableFuture<Void> completion) {}
}
