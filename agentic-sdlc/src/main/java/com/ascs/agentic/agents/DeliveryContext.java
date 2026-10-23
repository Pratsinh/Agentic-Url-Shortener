package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.model.Models;
import com.ascs.agentic.tools.WriteScope;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.model.Artifact;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The approved outcome of the discovery phase, handed to the delivery phase. It pins the exact artifact versions
 * (and their SHA-256) that humans approved; the delivery baseline stage re-checks those hashes against the store
 * before any code is written, so the plan executed is provably the plan approved.
 */
public record DeliveryContext(String requirement, Models.RequirementAnalysis analysis, String guidance, Models.Plan plan,
                              Artifact analysisArtifact, Artifact guidanceArtifact, Artifact planArtifact) {

    public static DeliveryContext fromStore(String requirement, ArtifactStore store) {
        Artifact a = store.latest(RequirementAnalystAgent.JSON).orElseThrow(() -> new IllegalStateException("no analysis"));
        Artifact g = store.latest(ClarificationAgent.GUIDANCE).orElseThrow(() -> new IllegalStateException("no guidance"));
        Artifact p = store.latest(PlannerAgent.JSON).orElseThrow(() -> new IllegalStateException("no approved plan"));
        return new DeliveryContext(requirement, Json.read(a.content(), Models.RequirementAnalysis.class), g.content(),
                Json.read(p.content(), Models.Plan.class), a, g, p);
    }

    public Models.PlanTask task(String id) {
        return plan.tasks().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown task " + id));
    }

    /** Least privilege for one task: its listed files, new files next to them, and its module's test tree. */
    public WriteScope scopeOf(Models.PlanTask t) {
        Set<String> files = new HashSet<>(), dirs = new HashSet<>();
        for (String f : t.files()) {
            String p = com.ascs.agentic.tools.PathGuard.normalize(f);
            files.add(p);
            int slash = p.lastIndexOf('/');
            if (slash > 0) dirs.add(p.substring(0, slash));
        }
        return new WriteScope(files, dirs, List.of(t.module() + "/src/test/"));
    }

    /** Repair and review-fix may touch anything any task of the approved plan may touch. */
    public WriteScope unionScope() {
        WriteScope all = new WriteScope(Set.of(), Set.of(), List.of());
        for (Models.PlanTask t : plan.tasks()) all = all.union(scopeOf(t));
        return all;
    }

    public String criteriaText() {
        StringBuilder sb = new StringBuilder();
        analysis.acceptanceCriteria().forEach(c -> sb.append("- ").append(c.id()).append(": ").append(c.text()).append('\n'));
        return sb.toString();
    }
}
