package org.blu3fishez.springpaper.example.listener;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class ExampleSignalEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
