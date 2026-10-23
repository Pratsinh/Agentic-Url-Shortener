package com.ascs.agentic.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from text before it is persisted (transcripts, reports). Matches common credential shapes
 * and, exactly, the values of credential-like environment variables of this process: the API key itself can never
 * reach a file even if a model or tool echoed it.
 */
public final class Redactor {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{10,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key|access[_-]?token)\\b(\\s*[:=]\\s*[\"']?)(?=[^\\s\"']*\\d)[A-Za-z0-9/+_.\\-]{10,}"));
    static final Pattern SECRET_ENV = Pattern.compile("(?i).*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL).*");

    private final List<String> literals;

    public Redactor(Map<String, String> env) {
        List<String> values = new ArrayList<>();
        env.forEach((k, v) -> {
            if (SECRET_ENV.matcher(k).matches() && v != null && v.length() >= 8) values.add(v);
        });
        this.literals = List.copyOf(values);
    }

    public static Redactor fromProcessEnv() {
        return new Redactor(System.getenv());
    }

    public String redact(String text) {
        if (text == null) return null;
        String out = text;
        for (String literal : literals) out = out.replace(literal, "[REDACTED:env]");
        for (int i = 0; i < PATTERNS.size(); i++) {
            Matcher m = PATTERNS.get(i).matcher(out);
            out = i == PATTERNS.size() - 1 ? m.replaceAll("$1$2[REDACTED]") : m.replaceAll("[REDACTED]");
        }
        return out;
    }
}
