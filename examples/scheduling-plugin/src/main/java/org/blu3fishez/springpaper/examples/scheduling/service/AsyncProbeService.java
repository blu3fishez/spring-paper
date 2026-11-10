package org.blu3fishez.springpaper.examples.scheduling.service;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Invoked through another bean: Spring's proxy is the asynchronous boundary. */
@Service
@RequiredArgsConstructor
public class AsyncProbeService {
    private final PaperThreadAccess threads;
    @Async("paperTasks")
    public CompletableFuture<String> verify() {
        if (threads.isPrimaryThread()) throw new IllegalStateException("Async ran on Paper main thread");
        return CompletableFuture.completedFuture("ASYNC_OK virtual=" + Thread.currentThread().isVirtual());
    }
}
