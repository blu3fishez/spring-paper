package org.blu3fishez.springpaper.examples.business.inbox;

import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.springframework.context.annotation.Conditional;

@PaperEventListener
@Conditional(InboxEnabled.class)
@RequiredArgsConstructor
public final class InboxListener implements Listener {
    private final InboxService inbox;
    @EventHandler public void onJoin(PlayerJoinEvent event) { inbox.show(event.getPlayer()); }
}
