package com.ascs.agentic.agents;

import com.ascs.agentic.config.AgenticConfig;
import com.ascs.agentic.human.HumanChannel;
import com.ascs.agentic.json.Json;
import com.ascs.agentic.llm.LlmLedger;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.workspace.BuildRunner;
import com.ascs.agentic.workspace.GitWorkspace;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.StageInput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Dependencies shared by the agents of one run. Agents are stateless apart from what they read through this. */
public record AgentKit(AgenticConfig config, AgentLoop loop, GitWorkspace workspace, BuildRunner build, AuditLog audit,
                       HumanChannel human, LlmLedger ledger) {

    private static final int MAX_CONVENTIONS = 14_000;

    /** System prompt: the shared operating rules plus the role's instructions (versioned resources). */
    public static String systemPrompt(String role) {
        return Json.text("prompts/common.md") + "\n" + Json.text("prompts/" + role + ".md");
    }

    /** The target repository's own engineering guide (CLAUDE.md, README) as context for planning and coding. */
    public String conventions() {
        for (String name : List.of("CLAUDE.md", "README.md")) {
            Path p = workspace.root().resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    String s = Files.readString(p, StandardCharsets.UTF_8);
                    return "Project guide (" + name + "):\n" + (s.length() > MAX_CONVENTIONS ? s.substring(0, MAX_CONVENTIONS) + "\n...[truncated]" : s);
                } catch (IOException e) {
                    return "(project guide unreadable)";
                }
            }
        }
        return "(no project guide found)";
    }

    /** Top-level layout: modules and their source roots, so the model starts oriented. */
    public String layout() {
        StringBuilder sb = new StringBuilder("Repository top level:\n");
        try (Stream<Path> s = Files.list(workspace.root())) {
            s.sorted().forEach(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith(".")) return;
                sb.append("- ").append(n).append(Files.isDirectory(p) ? "/" : "").append('\n');
            });
        } catch (IOException e) {
            sb.append("(unreadable)\n");
        }
        return sb.toString();
    }

    public static <T> T upstream(StageInput in, String artifact, Class<T> type) {
        return Json.read(content(in, artifact), type);
    }

    public static String content(StageInput in, String artifact) {
        return in.artifact(artifact).map(Artifact::content)
                .orElseThrow(() -> new IllegalStateException("missing upstream artifact " + artifact));
    }

    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n...[truncated " + (s.length() - max) + " characters]";
    }
}
