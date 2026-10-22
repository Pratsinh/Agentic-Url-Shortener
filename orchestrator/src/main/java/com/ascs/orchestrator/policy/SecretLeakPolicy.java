package com.ascs.orchestrator.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** SECURITY: blocks outputs that appear to contain credentials. Reports the kind and artifact, never the value. */
public final class SecretLeakPolicy implements Policy {

    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();
    static {
        PATTERNS.put("AWS access key id", Pattern.compile("AKIA[0-9A-Z]{16}"));
        PATTERNS.put("private key block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));
        PATTERNS.put("hard-coded credential", Pattern.compile(
                "(?i)\\b(password|passwd|secret|api[_-]?key|access[_-]?token)\\b\\s*[:=]\\s*[\"']?(?=[^\\s\"']*\\d)[A-Za-z0-9/+_.\\-]{10,}"));
    }

    @Override public String id() { return "security.secret-leak"; }
    @Override public PolicyCategory category() { return PolicyCategory.SECURITY; }

    @Override
    public List<Violation> evaluate(PolicyContext ctx) {
        List<Violation> out = new ArrayList<>();
        ctx.outputs().forEach((name, content) -> PATTERNS.forEach((label, p) -> {
            if (p.matcher(content).find()) {
                out.add(violation(Severity.BLOCK, "possible " + label + " in '" + name + "'"));
            }
        }));
        return out;
    }
}
