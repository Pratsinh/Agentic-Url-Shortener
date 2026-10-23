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
import java.util.List;
import java.util.Map;

/**
 * LLM agent: writes the change documentation into the workspace, in parallel with the review. Its write scope is
 * the single file {@code docs/changes/<runId>.md}; it must exist afterwards and is committed with the code.
 */
public final class DocsAgent implements Agent {

    public static final String ROLE = "docs-writer", JSON = "docs.json";

    private final AgentKit kit;
    private final DeliveryContext ctx;

    public DocsAgent(AgentKit kit, DeliveryContext ctx) {
        this.kit = kit;
        this.ctx = ctx;
    }

    public static String docPath(String runId) {
        return "docs/changes/" + runId + ".md";
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        String path = docPath(in.runId());
        String prompt = "Write the change documentation to exactly this file: " + path + "\n\nRequirement:\n" + ctx.requirement()
                + "\n\n## Acceptance criteria\n" + ctx.criteriaText() + "\n" + ctx.guidance() + "\n\n## Plan\n" + Render.plan(ctx.plan())
                + "\n\n## Verification (real results)\n" + AgentKit.truncate(AgentKit.content(in, VerificationAgent.VERIFY_JSON), 5_000)
                + "\n\n## Diff\n```diff\n" + AgentKit.truncate(AgentKit.content(in, VerificationAgent.PATCH), 60_000) + "\n```\n";
        AgentLoop.Outcome<Models.DocsResult> out = kit.loop().run(in, ROLE, kit.config().model(), "medium",
                kit.config().maxTokens(), AgentKit.systemPrompt(ROLE), prompt,
                new AgentLoop.Terminal<>("submit_docs", "Submit the documentation you wrote.",
                        Json.resource("schemas/docs-result.schema.json"), Models.DocsResult.class, r -> check(r, path)));
        return new AgentResult(Map.of(JSON, Json.pretty(out.value()), "change-notes.md",
                Files.readString(kit.workspace().root().resolve(path))), "documented in " + path + " (turns=" + out.turns() + ")");
    }

    private List<String> check(Models.DocsResult r, String path) {
        List<String> errors = new ArrayList<>();
        if (r.filesWritten().stream().map(PathGuard::normalize).noneMatch(path::equals)) errors.add("filesWritten must include " + path);
        if (!Files.isRegularFile(kit.workspace().root().resolve(path))) errors.add(path + " was not written");
        return errors;
    }
}
