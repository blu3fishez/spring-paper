package org.blu3fishez.springpaper.examples.minimal.listener;

import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.blu3fishez.springpaper.examples.minimal.service.GreetingService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

@RequiredArgsConstructor
@PaperEventListener
public final class GreetingListener implements Listener {
    private final GreetingService greetings;
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().sendPlainMessage(greetings.greet(event.getPlayer().getName()));
    }
}
