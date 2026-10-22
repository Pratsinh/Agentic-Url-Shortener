package com.ascs.orchestrator.core;

import com.ascs.orchestrator.model.StageDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated, immutable dependency graph (DAG). Construction fails fast on structural errors. */
public final class WorkflowGraph {

    private final Map<String, StageDefinition> stages = new LinkedHashMap<>();
    private final List<String> topoOrder;
    private final List<List<String>> levels;

    public WorkflowGraph(Collection<StageDefinition> definitions) {
        for (StageDefinition d : definitions) {
            if (stages.putIfAbsent(d.id(), d) != null) {
                throw new IllegalArgumentException("duplicate stage id: " + d.id());
            }
        }
        for (StageDefinition d : stages.values()) {
            for (String dep : d.dependsOn()) {
                if (dep.equals(d.id())) throw new IllegalArgumentException("stage depends on itself: " + d.id());
                if (!stages.containsKey(dep)) {
                    throw new IllegalArgumentException("stage '" + d.id() + "' depends on unknown stage '" + dep + "'");
                }
            }
        }
        // Kahn's algorithm, grouped into waves ("levels") of stages that may run in parallel.
        Map<String, Integer> remaining = new HashMap<>();
        stages.values().forEach(d -> remaining.put(d.id(), d.dependsOn().size()));
        List<String> order = new ArrayList<>();
        List<List<String>> waves = new ArrayList<>();
        List<String> wave = stages.keySet().stream().filter(id -> remaining.get(id) == 0).toList();
        while (!wave.isEmpty()) {
            waves.add(wave);
            order.addAll(wave);
            List<String> next = new ArrayList<>();
            for (String done : wave) {
                for (StageDefinition d : stages.values()) {
                    if (d.dependsOn().contains(done) && remaining.merge(d.id(), -1, Integer::sum) == 0) {
                        next.add(d.id());
                    }
                }
            }
            wave = next;
        }
        if (order.size() != stages.size()) {
            Set<String> stuck = new LinkedHashSet<>(stages.keySet());
            stuck.removeAll(order);
            throw new IllegalArgumentException("cycle detected involving stages: " + stuck);
        }
        this.topoOrder = List.copyOf(order);
        this.levels = waves.stream().map(List::copyOf).toList();
    }

    public StageDefinition stage(String id) {
        StageDefinition d = stages.get(id);
        if (d == null) throw new IllegalArgumentException("unknown stage: " + id);
        return d;
    }

    public Collection<StageDefinition> stages() { return stages.values(); }

    public List<String> topologicalOrder() { return topoOrder; }

    /** Parallel waves: every stage in wave N depends only on stages in earlier waves. */
    public List<List<String>> levels() { return levels; }

    public Set<String> ancestors(String id) {
        Set<String> out = new LinkedHashSet<>();
        collectAncestors(id, out);
        return out;
    }

    private void collectAncestors(String id, Set<String> out) {
        for (String dep : stage(id).dependsOn()) {
            if (out.add(dep)) collectAncestors(dep, out);
        }
    }
}
