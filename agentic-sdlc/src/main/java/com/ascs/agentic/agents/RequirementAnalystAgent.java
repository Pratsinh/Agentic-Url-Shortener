package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.model.Models;
import com.ascs.agentic.tools.PathGuard;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM agent: requirement understanding grounded in the codebase. Its semantic checks reject hallucinated
 * structure: impacted MODIFY/CONTEXT paths must exist in the repository, NEW ones must not, ids are unique, and an
 * AMBIGUOUS classification must come with the questions that make it ambiguous.
 */
public final class RequirementAnalystAgent implements Agent {

    public static final String ROLE = "requirements-analyst";
    public static final String JSON = "requirement-analysis.json", MD = "requirement-analysis.md";

    private final AgentKit kit;

    public RequirementAnalystAgent(AgentKit kit) {
        this.kit = kit;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        String prompt = "Requirement:\n<requirement>\n" + in.requirement() + "\n</requirement>\n\n" + kit.layout() + "\n"
                + kit.conventions();
        AgentLoop.Outcome<Models.RequirementAnalysis> out = kit.loop().run(in, ROLE, kit.config().model(), kit.config().effort(),
                kit.config().maxTokens(), AgentKit.systemPrompt(ROLE), prompt,
                new AgentLoop.Terminal<>("submit_requirement_analysis", "Submit the structured requirement analysis.",
                        Json.resource("schemas/requirement-analysis.schema.json"), Models.RequirementAnalysis.class, this::check));
        Models.RequirementAnalysis a = out.value();
        long blocking = a.ambiguities().stream().filter(Models.Ambiguity::blocking).count();
        return new AgentResult(Map.of(JSON, Json.pretty(a), MD, Render.analysis(a)),
                "kind=" + a.kind() + " criteria=" + a.acceptanceCriteria().size() + " ambiguities=" + a.ambiguities().size()
                        + " (blocking " + blocking + ") impacted=" + a.impactedAreas().size() + " turns=" + out.turns());
    }

    List<String> check(Models.RequirementAnalysis a) {
        List<String> errors = new ArrayList<>();
        unique(a.acceptanceCriteria().stream().map(Models.Criterion::id).toList(), "acceptance criterion", errors);
        unique(a.ambiguities().stream().map(Models.Ambiguity::id).toList(), "ambiguity", errors);
        if (a.kind().equals("AMBIGUOUS") && a.ambiguities().isEmpty()) {
            errors.add("kind AMBIGUOUS requires at least one ambiguity");
        }
        PathGuard guard;
        try {
            guard = new PathGuard(kit.workspace().root());
        } catch (java.io.IOException e) {
            return List.of("workspace unavailable: " + e.getMessage());
        }
        for (Models.ImpactedArea area : a.impactedAreas()) {
            try {
                boolean exists = Files.exists(guard.resolve(area.path()));
                if (!area.changeType().equals("NEW") && !exists) {
                    errors.add("impacted path '" + area.path() + "' (" + area.changeType() + ") does not exist; use list_files/search_code to find the real path");
                }
                if (area.changeType().equals("NEW") && exists) {
                    errors.add("impacted path '" + area.path() + "' already exists; use MODIFY");
                }
            } catch (Exception e) {
                errors.add("impacted path '" + area.path() + "' is invalid: " + e.getMessage());
            }
        }
        return errors;
    }

    static void unique(List<String> ids, String what, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) if (!seen.add(id)) errors.add("duplicate " + what + " id " + id);
    }
}
