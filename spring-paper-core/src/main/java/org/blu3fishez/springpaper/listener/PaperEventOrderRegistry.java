package org.blu3fishez.springpaper.listener;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Metadata for one batch. Native event discovery, inactive-role filtering and proxy handling stay in the core. */
public final class PaperEventOrderRegistry {
    private final Map<EventPriority, List<Selector>> prefixes = new EnumMap<>(EventPriority.class);
    private final List<Rule> rules = new ArrayList<>();

    /** Append ordered listener groups before all remaining handlers, separately for each native event list. */
    @SafeVarargs
    public final void first(EventPriority priority, Class<? extends Listener>... types) {
        for (var type : types) prefix(priority).add(new Type(Objects.requireNonNull(type)));
    }

    /** Same ordered prefix, identifying factory adapters by their Spring bean names. */
    public void firstBean(EventPriority priority, String... names) {
        for (var name : names) prefix(priority).add(new Bean(name));
    }

    @SafeVarargs
    public final void before(Class<? extends Event> event, EventPriority priority,
            Class<? extends Listener> before, Class<? extends Listener>... after) {
        for (var type : after) rule(event, priority, new Type(before), new Type(type));
    }

    public void beforeBeans(Class<? extends Event> event, EventPriority priority,
            Class<? extends Listener> before, String... after) {
        for (var name : after) rule(event, priority, new Type(before), new Bean(name));
    }

    @SafeVarargs
    public final void beforeBean(Class<? extends Event> event, EventPriority priority,
            String before, Class<? extends Listener>... after) {
        for (var type : after) rule(event, priority, new Bean(before), new Type(type));
    }

    private List<Selector> prefix(EventPriority priority) {
        return prefixes.computeIfAbsent(Objects.requireNonNull(priority), ignored -> new ArrayList<>());
    }

    private void rule(Class<? extends Event> event, EventPriority priority, Selector before, Selector after) {
        rules.add(new Rule(Objects.requireNonNull(event), Objects.requireNonNull(priority), before, after));
    }

    int prefixPosition(EventPriority priority, PaperListenerRegistrar.Candidate candidate) {
        var selected = prefixes.getOrDefault(priority, List.of());
        for (int i = 0; i < selected.size(); i++) if (selected.get(i).matches(candidate)) return i;
        return -1;
    }
    List<Rule> rules() { return List.copyOf(rules); }

    interface Selector { boolean matches(PaperListenerRegistrar.Candidate candidate); }
    private record Type(Class<? extends Listener> type) implements Selector {
        private Type { Objects.requireNonNull(type); }
        @Override public boolean matches(PaperListenerRegistrar.Candidate candidate) { return type.isAssignableFrom(candidate.type()); }
    }
    private record Bean(String name) implements Selector {
        private Bean {
            Objects.requireNonNull(name);
            if (name.isBlank()) throw new IllegalArgumentException("An event relationship needs a Spring bean name");
        }
        @Override public boolean matches(PaperListenerRegistrar.Candidate candidate) { return name.equals(candidate.name()); }
    }
    record Rule(Class<? extends Event> event, EventPriority priority, Selector before, Selector after) {}
}
