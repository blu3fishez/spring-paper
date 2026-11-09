package org.blu3fishez.springpaper.example.listener;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.blu3fishez.springpaper.annotation.PaperEventOrder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

@PaperEventListener
@RequiredArgsConstructor
public final class ExampleAfterListener implements Listener {
    private final ExampleListener first;
    @Getter private int signals;

    @EventHandler
    @PaperEventOrder(after = ExampleListener.class)
    public void onSignal(ExampleSignalEvent event) {
        if (first.signals() != 1) throw new IllegalStateException("Event relation was not honored");
        signals++;
    }
}
