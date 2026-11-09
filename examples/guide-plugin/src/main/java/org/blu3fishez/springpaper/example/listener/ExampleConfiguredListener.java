package org.blu3fishez.springpaper.example.listener;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Its cross-feature ordering is supplied by the composition configuration. */
@PaperEventListener
@RequiredArgsConstructor
public final class ExampleConfiguredListener implements Listener {
    private final ExampleAfterListener previous;
    @Getter private int signals;

    @EventHandler public void onSignal(ExampleSignalEvent event) {
        if (previous.getSignals() != 1) throw new IllegalStateException("Composition event relation was not honored");
        signals++;
    }
}
