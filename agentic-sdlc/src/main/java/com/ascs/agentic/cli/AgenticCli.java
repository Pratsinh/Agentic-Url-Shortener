package com.ascs.agentic.cli;

import com.ascs.agentic.config.AgenticConfig;
import com.ascs.agentic.human.ConsoleHumanChannel;
import com.ascs.agentic.human.FileHumanChannel;
import com.ascs.agentic.human.HumanChannel;
import com.ascs.agentic.json.Json;
import com.ascs.agentic.llm.AnthropicProvider;
import com.ascs.agentic.llm.Llm;
import com.ascs.agentic.llm.LlmLedger;
import com.ascs.agentic.llm.RecordingProvider;
import com.ascs.agentic.llm.Redactor;
import com.ascs.agentic.pipeline.AgenticPipeline;
import com.ascs.agentic.pipeline.RunRecorder;
import com.ascs.agentic.workspace.Commands;
import com.ascs.agentic.workspace.MavenBuildRunner;
import com.ascs.orchestrator.audit.AuditLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Command line entry point.
 * <pre>
 * run --scenario greenfield|brownfield|ambiguous | --requirement "text" | --requirement-file path
 *     [--approval console|file] [--approver name] [--run-id id]
 * doctor            check toolchain and credentials, then make one real model call
 * verify-audit ID   re-verify the persisted hash chain of a run
 * list              list recorded runs
 * </pre>
 * Run from the repository root (or set AGENTIC_REPO).
 */
public final class AgenticCli {

    private AgenticCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        Map<String, String> env = System.getenv();
        AgenticConfig config = AgenticConfig.fromEnv(env, Path.of(env.getOrDefault("AGENTIC_REPO", ".")));
        int code = switch (args[0]) {
            case "run" -> run(config, options(args));
            case "doctor" -> doctor(config);
            case "verify-audit" -> verifyAudit(config, args.length > 1 ? args[1] : "");
            case "list" -> list(config);
            default -> {
                usage();
                yield 2;
            }
        };
        System.exit(code);
    }

    static int run(AgenticConfig config, Map<String, String> o) throws Exception {
        String label;
        String requirement;
        if (o.containsKey("scenario")) {
            label = o.get("scenario");
            if (!Set.of("greenfield", "brownfield", "ambiguous").contains(label)) throw new IllegalArgumentException("unknown scenario " + label);
            requirement = Json.text("scenarios/" + label + ".md").strip();
        } else if (o.containsKey("requirement-file")) {
            label = "custom";
            requirement = Files.readString(Path.of(o.get("requirement-file")), StandardCharsets.UTF_8).strip();
        } else if (o.containsKey("requirement")) {
            label = "custom";
            requirement = o.get("requirement").strip();
        } else {
            usage();
            return 2;
        }
        if (!config.credentialsConfigured()) {
            System.err.println("ANTHROPIC_API_KEY is not set: this system only runs with a real model (see docs/AGENTIC_SETUP.md).");
            return 3;
        }
        String runId = o.getOrDefault("run-id", label.substring(0, Math.min(5, label.length())) + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        String mode = o.getOrDefault("approval", System.console() != null ? "console" : "file");
        String approver = o.getOrDefault("approver", System.getProperty("user.name", "operator"));
        Function<Path, HumanChannel> human = mode.equals("console")
                ? inbox -> new ConsoleHumanChannel(System.in, System.out, approver)
                : inbox -> new FileHumanChannel(inbox, config.approvalTimeout(), Duration.ofSeconds(2));
        AgenticPipeline pipeline = new AgenticPipeline(config, AnthropicProvider.fromEnv(config.refusalFallback()),
                new MavenBuildRunner(config.mavenCommand(), Set.of(config.verifyModule()), config.buildTimeout()),
                human, Clock.systemUTC(), Redactor.fromProcessEnv());
        System.out.println("run " + runId + " (" + label + "), model " + config.model() + ", approvals via " + mode
                + "\nrecord: " + config.runsDir().resolve(runId));
        AgenticPipeline.Result r = pipeline.run(runId, requirement, label);
        System.out.println("\nstatus: " + r.status() + "\nbranch: " + r.branch() + (r.commit() == null ? "" : "\ncommit: " + r.commit())
                + "\nreport: " + r.runDir().resolve("summary.md"));
        return r.status().equals("DELIVERED") ? 0 : 1;
    }

    static int doctor(AgenticConfig config) throws Exception {
        List<String> problems = new ArrayList<>();
        System.out.println("repository: " + config.repoRoot() + (Files.isDirectory(config.repoRoot().resolve(".git")) ? "" : "  (NOT a git repository)"));
        if (!Files.isDirectory(config.repoRoot().resolve(".git"))) problems.add("not a git repository");
        System.out.println("java: " + System.getProperty("java.version") + " at " + System.getProperty("java.home"));
        for (List<String> cmd : List.of(List.of("git", "--version"), List.of(config.mavenCommand(), "-v"))) {
            try {
                Commands.Result r = Commands.run(cmd, config.repoRoot(), Duration.ofSeconds(60), Map.of("JAVA_HOME", System.getProperty("java.home")));
                System.out.println(cmd.get(0) + ": " + (r.ok() ? r.output().lines().findFirst().orElse("ok") : "FAILED"));
                if (!r.ok()) problems.add(cmd.get(0) + " not working");
            } catch (Exception e) {
                System.out.println(cmd.get(0) + ": NOT FOUND (" + e.getMessage() + ")");
                problems.add(cmd.get(0) + " not found");
            }
        }
        System.out.println("model: " + config.model() + " (review: " + config.reviewModel() + "), effort " + config.effort()
                + ", refusal fallback " + (config.refusalFallback() ? "on" : "off"));
        System.out.println("credentials: " + (config.credentialsConfigured() ? "configured (value not shown)" : "MISSING: set ANTHROPIC_API_KEY"));
        if (!config.credentialsConfigured()) {
            problems.add("no model credentials");
        } else {
            LlmLedger ledger = new LlmLedger(1.0);
            AuditLog audit = new AuditLog(Clock.systemUTC());
            Llm.Provider p = new RecordingProvider(AnthropicProvider.fromEnv(config.refusalFallback()), ledger, audit, Clock.systemUTC(),
                    Redactor.fromProcessEnv(), (n, c) -> { });
            try {
                Llm.Turn t = p.start(new Llm.Spec("doctor", "doctor", "doctor", config.model(), "Reply with the single word: ready",
                        List.of(), "low", 1024)).send(List.of(new Llm.Text("Are you ready?")));
                System.out.println("live model call: OK model=" + t.model() + " request_id=" + t.requestId() + " tokens in/out="
                        + t.usage().inputTokens() + "/" + t.usage().outputTokens() + " reply=" + t.text().strip());
            } catch (Exception e) {
                System.out.println("live model call: FAILED " + e.getMessage());
                problems.add("model call failed");
            }
        }
        System.out.println(problems.isEmpty() ? "doctor: all checks passed" : "doctor: problems: " + problems);
        return problems.isEmpty() ? 0 : 1;
    }

    static int verifyAudit(AgenticConfig config, String runId) throws Exception {
        Path audit = config.runsDir().resolve(runId).resolve("audit.jsonl");
        if (!Files.isRegularFile(audit)) {
            System.err.println("no audit log at " + audit);
            return 2;
        }
        boolean ok = RunRecorder.verifyPersistedAudit(audit);
        System.out.println(audit + ": hash chain " + (ok ? "verified intact" : "BROKEN (tampered or truncated)"));
        return ok ? 0 : 1;
    }

    static int list(AgenticConfig config) throws Exception {
        if (!Files.isDirectory(config.runsDir())) {
            System.out.println("no runs");
            return 0;
        }
        try (Stream<Path> s = Files.list(config.runsDir())) {
            for (Path d : s.sorted().toList()) {
                Path state = d.resolve("state.json");
                String status = Files.isRegularFile(state) ? Json.parse(Files.readString(state)).path("status").asText() : "(in progress or crashed)";
                System.out.println(d.getFileName() + "  " + status);
            }
        }
        return 0;
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument " + args[i]);
            String key = args[i].substring(2);
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for --" + key);
            o.put(key, args[++i]);
        }
        return o;
    }

    private static void usage() {
        System.err.println("""
                usage: agentic run --scenario greenfield|brownfield|ambiguous [--approval console|file] [--approver NAME] [--run-id ID]
                       agentic run --requirement "TEXT" | --requirement-file PATH [...]
                       agentic doctor | verify-audit RUN_ID | list""");
    }
}
