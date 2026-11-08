package org.blu3fishez.springpaper.example.listener;

import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

@PaperEventListener
public final class ExampleListener implements Listener {
    private final AtomicInteger signals = new AtomicInteger();
    @EventHandler public void onSignal(ExampleSignalEvent event) { signals.incrementAndGet(); }
    public int signals() { return signals.get(); }
}
