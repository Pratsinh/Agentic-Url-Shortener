package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic security and change-control scan of the final diff (no model, so it cannot be talked out of a
 * finding). Rules look at added lines only and report the rule, file and line, never the matched text. CRITICAL or
 * HIGH findings fail the stage's exit gate, so nothing reaches the release approval.
 */
public final class SecurityScanAgent implements Agent {

    public static final String JSON = "security.json", MD = "security.md";

    public record Finding(String severity, String rule, String file, int line, String message) {}

    record Rule(String id, String severity, Pattern pattern, boolean mainOnly, boolean testOnly, String message) {}

    static final List<Rule> RULES = List.of(
            new Rule("secret.aws-key", "CRITICAL", Pattern.compile("AKIA[0-9A-Z]{16}"), false, false, "possible AWS access key"),
            new Rule("secret.private-key", "CRITICAL", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), false, false, "private key material"),
            new Rule("secret.anthropic-key", "CRITICAL", Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{10,}"), false, false, "model API key"),
            new Rule("secret.assignment", "HIGH", Pattern.compile(
                    "(?i)\\b(password|passwd|secret|api[_-]?key|access[_-]?token)\\b\\s*[:=]\\s*[\"'][^\"']{8,}[\"']"), true, false, "hard-coded credential"),
            new Rule("exec.process", "HIGH", Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|new\\s+ProcessBuilder\\s*\\("), true, false,
                    "process execution in production code"),
            new Rule("tls.trust-all", "HIGH", Pattern.compile("(?i)TrustAll|X509TrustManager|setHostnameVerifier|ALLOW_ALL_HOSTNAME"), true, false,
                    "TLS verification bypass"),
            new Rule("tests.disabled", "HIGH", Pattern.compile("@Disabled|@Ignore\\b"), false, true, "test disabled by the change"),
            new Rule("sql.concat", "MEDIUM", Pattern.compile("(?i)\"\\s*(select|insert|update|delete)\\b[^\"]*\"\\s*\\+"), true, false,
                    "query built by string concatenation"),
            new Rule("log.full-url", "MEDIUM", Pattern.compile("LOG\\.log\\([^;]*(originalUrl\\(\\)|\\burl\\b)"), true, false,
                    "logs a full URL (project rule: host only)"),
            new Rule("cors.wildcard", "MEDIUM", Pattern.compile("@CrossOrigin\\(\\s*(origins\\s*=\\s*)?\"\\*\""), true, false, "wildcard CORS"),
            new Rule("debug.stacktrace", "LOW", Pattern.compile("\\.printStackTrace\\(\\)"), true, false, "printStackTrace"),
            new Rule("debug.stdout", "LOW", Pattern.compile("System\\.(out|err)\\.print"), true, false, "console output in production code"));

    private final AgentKit kit;

    public SecurityScanAgent(AgentKit kit) {
        this.kit = kit;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        String diff = kit.workspace().diff();
        List<Finding> findings = scan(diff);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rules", RULES.size());
        report.put("findings", findings);
        report.put("blocking", findings.stream().filter(SecurityScanAgent::blocking).count());
        StringBuilder md = new StringBuilder("# Security & change-control scan\n\n").append(RULES.size())
                .append(" deterministic rules over the added lines of the final diff.\n\n");
        if (findings.isEmpty()) md.append("No findings.\n");
        findings.forEach(f -> md.append("- **").append(f.severity()).append("** `").append(f.file()).append(':').append(f.line())
                .append("` ").append(f.rule()).append(": ").append(f.message()).append('\n'));
        return new AgentResult(Map.of(JSON, Json.pretty(report), MD, md.toString()),
                findings.size() + " finding(s), " + report.get("blocking") + " blocking");
    }

    static boolean blocking(Finding f) {
        return f.severity().equals("CRITICAL") || f.severity().equals("HIGH");
    }

    public static List<Finding> scan(String diff) {
        List<Finding> out = new ArrayList<>();
        String file = null;
        int line = 0;
        for (String l : diff.split("\\R")) {
            if (l.startsWith("+++ ")) {
                file = l.startsWith("+++ b/") ? l.substring(6) : null;
                continue;
            }
            if (l.startsWith("deleted file mode") || l.startsWith("--- ")) continue;
            if (l.startsWith("@@")) {
                java.util.regex.Matcher m = Pattern.compile("\\+(\\d+)").matcher(l);
                line = m.find() ? Integer.parseInt(m.group(1)) - 1 : 0;
                continue;
            }
            if (file == null) continue;
            if (l.startsWith("+")) {
                line++;
                boolean test = file.contains("/src/test/");
                boolean main = file.contains("/src/main/");
                for (Rule r : RULES) {
                    if ((r.mainOnly() && !main) || (r.testOnly() && !test)) continue;
                    if (r.pattern().matcher(l).find()) out.add(new Finding(r.severity(), r.id(), file, line, r.message()));
                }
            } else if (!l.startsWith("-")) {
                line++;
            }
        }
        for (String block : diff.split("(?=diff --git )")) {
            if (block.contains("deleted file mode") && block.contains("/src/test/")) {
                String name = block.lines().findFirst().orElse("").replaceFirst("diff --git a/(\\S+).*", "$1");
                out.add(new Finding("HIGH", "tests.deleted", name, 0, "test file deleted by the change"));
            }
        }
        return out;
    }

    /** Exit gate: no CRITICAL or HIGH finding. */
    public static String gateFailure(String json) {
        long blocking = Json.parse(json).path("blocking").asLong();
        return blocking == 0 ? null : blocking + " blocking security finding(s)";
    }
}
