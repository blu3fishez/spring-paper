package org.blu3fishez.springpaper.lifecycle;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;

/** Resolves Spring-selected endpoints and validates both plans before any Paper hook is attempted. */
final class PaperLifecyclePlan {
    private final List<PaperLifecycleHook> hooks;
    private final LifecycleGraph stop;
    private final List<Integer> startup;

    PaperLifecyclePlan(ApplicationContext context, Map<String, Class<?>> candidates) {
        hooks = PaperLifecycleMetadata.read(context, candidates);
        var owners = new IdentityHashMap<Object, Integer>();
        for (int i = 0; i < hooks.size(); i++) {
            Integer prior = owners.put(hooks.get(i).bean(), i);
            if (prior != null) throw new IllegalArgumentException("One Paper lifecycle owner has multiple bean definitions: "
                    + hooks.get(prior).name() + ", " + hooks.get(i).name() + "; use a Spring alias instead");
        }
        var names = hooks.stream().map(PaperLifecycleHook::name).toList();
        var start = new LifecycleGraph(names, "startup");
        stop = new LifecycleGraph(names, "shutdown");
        context.getBeansOfType(PaperLifecycleRelation.class).forEach((name, relation) -> {
            int first = endpoint(name, relation.first(), relation.stage(), owners);
            int next = endpoint(name, relation.next(), relation.stage(), owners);
            (relation.stage() == PaperLifecycleRelation.Stage.STARTUP ? start : stop).before(first, next);
        });
        // Like Spring LifecycleProcessor's dependency ordering, explicit edges take precedence over phases.
        // Legacy numbers are only the stable ready-node fallback; new consumers need no phase catalogue.
        startup = start.order(Comparator.<Integer>comparingInt(i -> hooks.get(i).phase())
                .thenComparing(i -> hooks.get(i).name()));
        stop.order(Comparator.comparing(names::get));
    }

    List<Integer> startup() { return startup; }
    PaperLifecycleHook hook(int position) { return hooks.get(position); }

    List<Integer> shutdown(List<Integer> attempted) {
        int[] activation = new int[hooks.size()];
        java.util.Arrays.fill(activation, -1);
        for (int i = 0; i < attempted.size(); i++) activation[attempted.get(i)] = i;
        // Reverse activation is a tie-break, never extra edges: an explicit stop dependency can differ.
        // Order the full validated graph before filtering, retaining paths through unattempted owners.
        return stop.order(Comparator.<Integer>comparingInt(i -> hooks.get(i).stopPhase()).reversed()
                        .thenComparing(Comparator.<Integer>comparingInt(i -> activation[i]).reversed())
                        .thenComparing(i -> hooks.get(i).name()))
                .stream().filter(i -> activation[i] >= 0).toList();
    }

    private int endpoint(String relationName, Object owner, PaperLifecycleRelation.Stage stage,
            IdentityHashMap<Object, Integer> owners) {
        Integer position = owners.get(owner);
        if (position == null) throw new IllegalArgumentException(relationName + ": " + stage
                + " endpoint must be a registered Spring Paper lifecycle owner: " + owner.getClass().getName());
        var hook = hooks.get(position);
        if (stage == PaperLifecycleRelation.Stage.STARTUP ? hook.start() == null : hook.stop() == null) {
            throw new IllegalArgumentException(relationName + ": " + hook.name() + " has no Paper "
                    + stage.name().toLowerCase(java.util.Locale.ROOT) + " hook");
        }
        return position;
    }
}
