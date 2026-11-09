package org.blu3fishez.springpaper.listener;

import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** A composition-root relationship scoped to one native event list and priority. */
public record PaperEventRelation(Class<? extends Event> event, EventPriority priority,
                                 Class<? extends Listener> before, Class<? extends Listener> after) {
    public PaperEventRelation {
        Objects.requireNonNull(event); Objects.requireNonNull(priority);
        Objects.requireNonNull(before); Objects.requireNonNull(after);
        if (before == after) throw new IllegalArgumentException("An event relation needs two different listener types");
    }
}
