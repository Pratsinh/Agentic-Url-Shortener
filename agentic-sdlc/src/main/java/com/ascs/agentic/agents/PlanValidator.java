package com.ascs.agentic.agents;

import com.ascs.agentic.model.Models;
import com.ascs.agentic.tools.PathGuard;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.StageDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic checks and adjustments of a model-proposed plan, before any human sees it:
 * structure (unique ids, known dependencies, acyclic: validated by the engine's own {@link WorkflowGraph}),
 * scope (files inside writable modules, no build descriptors), and coverage (every acceptance criterion is owned by
 * a task, every referenced criterion exists). Then tasks that touch a common file are serialized, so parallel
 * implementers never edit the same file: those edges are recorded as orchestrator adjustments, not model output.
 */
public final class PlanValidator {

    private PlanValidator() {}

    public static List<String> validate(Models.Plan plan, Models.RequirementAnalysis analysis, List<String> writableModules) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Models.PlanTask t : plan.tasks()) if (!ids.add(t.id())) errors.add("duplicate task id " + t.id());
        Set<String> knownCriteria = new HashSet<>();
        analysis.acceptanceCriteria().forEach(c -> knownCriteria.add(c.id()));
        Set<String> covered = new HashSet<>();
        for (Models.PlanTask t : plan.tasks()) {
            for (String d : t.dependsOn()) {
                if (!ids.contains(d)) errors.add(t.id() + " depends on unknown task " + d);
                if (d.equals(t.id())) errors.add(t.id() + " depends on itself");
            }
            for (String c : t.acceptanceCriteria()) {
                if (!knownCriteria.contains(c)) errors.add(t.id() + " references unknown criterion " + c);
                covered.add(c);
            }
            for (String f : t.files()) {
                String p;
                try {
                    p = PathGuard.normalize(f);
                } catch (IllegalArgumentException e) {
                    errors.add(t.id() + " file '" + f + "': " + e.getMessage());
                    continue;
                }
                if (writableModules.stream().noneMatch(m -> p.startsWith(m + "/"))) {
                    errors.add(t.id() + " file '" + f + "' is outside the writable modules " + writableModules);
                }
                if (p.endsWith("pom.xml")) errors.add(t.id() + " must not change build descriptors (" + f + ")");
            }
        }
        for (String c : knownCriteria) if (!covered.contains(c)) errors.add("acceptance criterion " + c + " is not covered by any task");
        if (errors.isEmpty()) {
            try {
                new WorkflowGraph(plan.tasks().stream().map(t -> StageDefinition.builder(t.id())
                        .dependsOn(t.dependsOn().toArray(String[]::new)).build()).toList());
            } catch (IllegalArgumentException e) {
                errors.add("task graph is invalid: " + e.getMessage());
            }
        }
        return errors;
    }

    /** Adds an edge between any two tasks that share a file and are not already ordered; returns the adjusted plan. */
    public static Models.Plan serializeSharedFiles(Models.Plan plan) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        plan.tasks().forEach(t -> deps.put(t.id(), new LinkedHashSet<>(t.dependsOn())));
        List<String> adjustments = new ArrayList<>();
        List<Models.PlanTask> tasks = plan.tasks();
        for (int j = 0; j < tasks.size(); j++) {
            for (int i = 0; i < j; i++) {
                Models.PlanTask a = tasks.get(i), b = tasks.get(j);
                Set<String> shared = new TreeSet<>(normalized(a.files()));
                shared.retainAll(normalized(b.files()));
                if (shared.isEmpty() || reaches(deps, b.id(), a.id()) || reaches(deps, a.id(), b.id())) continue;
                deps.get(b.id()).add(a.id());
                adjustments.add(b.id() + " now runs after " + a.id() + " (both change " + shared + ")");
            }
        }
        List<Models.PlanTask> adjusted = tasks.stream().map(t -> t.withDependsOn(List.copyOf(deps.get(t.id())))).toList();
        return plan.withAdjustments(adjusted, adjustments);
    }

    private static Set<String> normalized(List<String> files) {
        Set<String> out = new HashSet<>();
        for (String f : files) out.add(PathGuard.normalize(f));
        return out;
    }

    /** True if {@code from} transitively depends on {@code to}. */
    static boolean reaches(Map<String, Set<String>> deps, String from, String to) {
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>(deps.get(from));
        while (!stack.isEmpty()) {
            String n = stack.remove(stack.size() - 1);
            if (n.equals(to)) return true;
            if (seen.add(n)) stack.addAll(deps.getOrDefault(n, Set.of()));
        }
        return false;
    }
}
