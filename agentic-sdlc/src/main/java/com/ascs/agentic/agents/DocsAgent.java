package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.model.Models;
import com.ascs.agentic.tools.PathGuard;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LLM agent: documents the change, in parallel with the review. Its write scope is exactly two files: the change
 * document {@code docs/changes/<runId>.md} (user stories traced to every acceptance criterion, API examples,
 * behaviour, compatibility, testing) and the architecture document {@link #DESIGN_DOC}, which it must update when the
 * API contract changed. Both rules are checked deterministically; a violation is returned to the model to correct.
 * Everything it writes is committed with the code.
 */
public final class DocsAgent implements Agent {

    public static final String ROLE = "docs-writer", JSON = "docs.json";
    public static final String DESIGN_DOC = "docs/TECHNICAL_DESIGN.md";
    /** A change under this prefix alters the published API contract, so the architecture document must follow. */
    static final String CONTRACT_PREFIX = "shortener-api/";

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
        boolean contractChanged = kit.workspace().changedFiles().stream().anyMatch(f -> f.startsWith(CONTRACT_PREFIX));
        String prompt = "Write the change documentation to exactly this file: " + path + "\n"
                + "It must start with a '## User stories' section: one story per line in the form 'As a <role>, I want <capability>, "
                + "so that <benefit> (AC-n, ...)', together covering every acceptance criterion id below.\n"
                + (contractChanged
                ? "The API contract changed: you MUST also update " + DESIGN_DOC + " (API specification and any affected design, "
                        + "data or failure-mode sections) with replace_in_file, keeping its structure.\n"
                : "If the change affects the architecture, also update " + DESIGN_DOC + " with replace_in_file, keeping its structure.\n")
                + "\nRequirement:\n" + ctx.requirement()
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
        Path doc = kit.workspace().root().resolve(path);
        if (!Files.isRegularFile(doc)) {
            errors.add(path + " was not written");
            return errors;
        }
        try {
            List<String> changed = kit.workspace().changedFiles();
            errors.addAll(docRules(Files.readString(doc), ctx.analysis().acceptanceCriteria().stream().map(Models.Criterion::id).toList(),
                    changed.stream().anyMatch(f -> f.startsWith(CONTRACT_PREFIX)), changed.contains(DESIGN_DOC)));
        } catch (IOException e) {
            errors.add("could not check the documentation: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.add("interrupted while checking the documentation");
        }
        return errors;
    }

    /**
     * The documentation rules, as data so they are testable: a user-stories section with at least one story, every
     * acceptance criterion traced, and the architecture document updated whenever the API contract changed.
     */
    public static List<String> docRules(String doc, List<String> criteriaIds, boolean contractChanged, boolean designDocChanged) {
        List<String> errors = new ArrayList<>();
        int at = doc.indexOf("## User stories");
        String stories = at < 0 ? "" : doc.substring(at);
        if (at < 0 || !Pattern.compile("(?im)^\\s*[-*]?\\s*As an? .+, I want .+, so that .+").matcher(stories).find()) {
            errors.add("the change document needs a '## User stories' section with stories 'As a <role>, I want <capability>, so that <benefit> (AC-n)'");
        }
        List<String> untraced = criteriaIds.stream().filter(id -> !Pattern.compile("\\b" + Pattern.quote(id) + "\\b").matcher(stories).find()).toList();
        if (!untraced.isEmpty()) errors.add("the user stories do not cover acceptance criteria " + untraced);
        if (contractChanged && !designDocChanged) {
            errors.add("the API contract changed, so " + DESIGN_DOC + " must be updated (API specification and affected sections)");
        }
        return errors;
    }
}
