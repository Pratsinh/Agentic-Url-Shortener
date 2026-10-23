package com.ascs.agentic.tools;

import com.ascs.orchestrator.policy.PolicyCategory;
import com.ascs.orchestrator.policy.Severity;
import com.ascs.orchestrator.policy.Violation;
import com.ascs.orchestrator.tools.Risk;
import com.ascs.orchestrator.tools.ToolCall;
import com.ascs.orchestrator.tools.ToolGateway;
import com.ascs.orchestrator.tools.ToolPolicy;
import com.ascs.orchestrator.tools.ToolSpec;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Change control and least privilege for workspace writes, enforced by the gateway before a write executes:
 * <ul>
 *   <li>CRITICAL (call denied, tool access revoked for the run, stage safe-stops): CI/CD, deployment, VCS internals,
 *       this system's own state, and the project scripts. An agent that tries these is not retried.</li>
 *   <li>BLOCK (call denied, the agent is told why and may continue): build descriptors (dependency changes need a
 *       human-authored change), and any path outside the write scope the approved plan gave the calling stage.</li>
 * </ul>
 */
public final class WorkspaceWritePolicy implements ToolPolicy {

    static final List<String> CRITICAL_PREFIXES = List.of(".github/", ".gitlab", ".agentic/", "deploy/", "scripts/", ".idea/", ".mvn/");
    static final List<String> CRITICAL_NAMES = List.of("Jenkinsfile", ".gitlab-ci.yml", ".gitattributes", ".gitignore", "CLAUDE.md");
    static final List<String> BLOCK_NAMES = List.of("pom.xml", "docker-compose.yml", "mvnw", "mvnw.cmd");

    private final Function<String, WriteScope> scopes;
    private final Predicate<String> existedAtBase;

    /**
     * @param scopes        stage id to write scope (null: the stage may not write)
     * @param existedAtBase whether a path exists in the workspace's base commit
     */
    public WorkspaceWritePolicy(Function<String, WriteScope> scopes, Predicate<String> existedAtBase) {
        this.scopes = scopes;
        this.existedAtBase = existedAtBase;
    }

    @Override
    public List<Violation> evaluate(ToolSpec tool, ToolCall call) {
        throw new UnsupportedOperationException("context-aware policy: the gateway supplies the calling stage");
    }

    @Override
    public List<Violation> evaluate(ToolGateway.Context ctx, ToolSpec tool, ToolCall call) {
        if (tool.risk() != Risk.WORKSPACE_WRITE) return List.of();
        String raw = call.args().get("path");
        if (raw == null) return List.of();
        String path;
        try {
            path = PathGuard.normalize(raw);
        } catch (IllegalArgumentException e) {
            return List.of(v(Severity.CRITICAL, "unsafe path: " + e.getMessage()));
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        String lower = path.toLowerCase(Locale.ROOT);
        if (CRITICAL_PREFIXES.stream().anyMatch(lower::startsWith) || CRITICAL_NAMES.contains(name)
                || lower.contains("application-prod")) {
            return List.of(v(Severity.CRITICAL, "protected path '" + path + "' (CI/CD, deployment, VCS or system state)"));
        }
        if (BLOCK_NAMES.contains(name)) {
            return List.of(v(Severity.BLOCK, "build descriptor '" + path + "' is change-controlled: dependency or build "
                    + "changes need a human-authored change; explain the need in your result instead"));
        }
        WriteScope scope = scopes.apply(ctx.stageId());
        if (scope == null) return List.of(v(Severity.BLOCK, "stage '" + ctx.stageId() + "' has no write permission"));
        if (!scope.allows(path, existedAtBase.test(path))) {
            return List.of(v(Severity.BLOCK, "'" + path + "' is outside this stage's approved write scope; allowed: " + scope.describe()));
        }
        return List.of();
    }

    private Violation v(Severity s, String message) {
        return new Violation("change-control.workspace-write", PolicyCategory.CHANGE_CONTROL, s, message);
    }

    /** Convenience for wiring: an immutable map lookup. */
    public static Function<String, WriteScope> fixed(Map<String, WriteScope> m) {
        return m::get;
    }
}
