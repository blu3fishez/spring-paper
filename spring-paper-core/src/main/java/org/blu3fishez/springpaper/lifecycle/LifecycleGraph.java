package org.blu3fishez.springpaper.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** A plan-local DAG, addressed by metadata positions rather than mutable consumer objects. */
final class LifecycleGraph {
    private final List<Set<Integer>> edges = new ArrayList<>();
    private final List<String> names;
    private final String stage;

    LifecycleGraph(List<String> names, String stage) {
        this.names = List.copyOf(names);
        this.stage = stage;
        names.forEach(ignored -> edges.add(new LinkedHashSet<>()));
    }

    void before(int first, int next) { edges.get(first).add(next); }

    List<Integer> order(Comparator<Integer> fallback) {
        int[] remaining = new int[names.size()];
        edges.forEach(targets -> targets.forEach(target -> remaining[target]++));
        var ready = new PriorityQueue<>(fallback);
        for (int i = 0; i < remaining.length; i++) if (remaining[i] == 0) ready.add(i);
        List<Integer> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            int first = ready.remove(); ordered.add(first);
            for (int next : edges.get(first)) if (--remaining[next] == 0) ready.add(next);
        }
        if (ordered.size() != names.size()) {
            throw new IllegalArgumentException("Cyclic Paper " + stage + " relationships: " + cycle());
        }
        return List.copyOf(ordered);
    }

    private List<String> cycle() {
        int[] state = new int[names.size()];
        List<Integer> path = new ArrayList<>();
        for (int i = 0; i < state.length; i++) {
            List<String> found = visit(i, state, path);
            if (!found.isEmpty()) return found;
        }
        throw new IllegalStateException("A cyclic plan must contain a cycle");
    }

    private List<String> visit(int node, int[] state, List<Integer> path) {
        if (state[node] == 2) return List.of();
        if (state[node] == 1) {
            List<String> found = new ArrayList<>();
            path.subList(path.indexOf(node), path.size()).forEach(index -> found.add(names.get(index)));
            found.add(names.get(node));
            return found;
        }
        state[node] = 1; path.add(node);
        for (int next : edges.get(node)) {
            List<String> found = visit(next, state, path);
            if (!found.isEmpty()) return found;
        }
        path.removeLast(); state[node] = 2;
        return List.of();
    }
}
