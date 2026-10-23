package com.ascs.agentic.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration, read from the environment. Credentials are never stored here: the Anthropic SDK reads
 * {@code ANTHROPIC_API_KEY} (or its other credential sources) itself, and this record only knows whether one is set.
 *
 * @param maxRunCostUsd   estimated spend after which further model calls in a run are refused (cost guardrail)
 * @param maxParallel     worker threads, i.e. how many independent stages may run at once
 * @param verifyModule    Maven module whose tests gate a change (built with its upstream modules)
 * @param writableModules modules an implementation task may change
 */
public record AgenticConfig(Path repoRoot, Path runsDir, Path worktreesDir, String model, String reviewModel,
                            String effort, int maxTokens, boolean refusalFallback, double maxRunCostUsd,
                            int maxLlmTurns, String mavenCommand, int maxParallel, Duration stageTimeout,
                            Duration approvalTimeout, Duration buildTimeout, String verifyModule,
                            List<String> writableModules, boolean credentialsConfigured) {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";

    public AgenticConfig {
        writableModules = List.copyOf(writableModules);
        if (maxParallel < 1 || maxParallel > 8) throw new IllegalArgumentException("AGENTIC_MAX_PARALLEL must be 1..8");
        if (maxTokens < 1024) throw new IllegalArgumentException("AGENTIC_MAX_TOKENS must be >= 1024");
    }

    public static AgenticConfig fromEnv(Map<String, String> env, Path repoRoot) {
        Path root = repoRoot.toAbsolutePath().normalize();
        String model = env.getOrDefault("AGENTIC_MODEL", DEFAULT_MODEL);
        return new AgenticConfig(root, root.resolve(".agentic").resolve("runs"), root.resolve(".agentic").resolve("worktrees"),
                model, env.getOrDefault("AGENTIC_REVIEW_MODEL", model),
                env.getOrDefault("AGENTIC_EFFORT", "high").toLowerCase(Locale.ROOT),
                intOf(env, "AGENTIC_MAX_TOKENS", 16000),
                !"off".equalsIgnoreCase(env.getOrDefault("AGENTIC_REFUSAL_FALLBACK", "default")),
                Double.parseDouble(env.getOrDefault("AGENTIC_MAX_RUN_USD", "40")),
                intOf(env, "AGENTIC_MAX_LLM_TURNS", 60),
                env.getOrDefault("AGENTIC_MVN", defaultMaven(env)),
                intOf(env, "AGENTIC_MAX_PARALLEL", 2),
                Duration.ofMinutes(intOf(env, "AGENTIC_STAGE_TIMEOUT_MINUTES", 60)),
                Duration.ofMinutes(intOf(env, "AGENTIC_APPROVAL_TIMEOUT_MINUTES", 120)),
                Duration.ofMinutes(intOf(env, "AGENTIC_BUILD_TIMEOUT_MINUTES", 20)),
                "shortener-service", List.of("shortener-service", "shortener-api"),
                isSet(env, "ANTHROPIC_API_KEY") || isSet(env, "ANTHROPIC_AUTH_TOKEN"));
    }

    /** Prefers the Maven of MAVEN_HOME / M2_HOME; otherwise relies on PATH ({@code mvn.cmd} on Windows). */
    static String defaultMaven(Map<String, String> env) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "mvn.cmd" : "mvn";
        for (String var : List.of("MAVEN_HOME", "M2_HOME")) {
            String home = env.get(var);
            if (home != null && Files.isRegularFile(Path.of(home, "bin", exe))) return Path.of(home, "bin", exe).toString();
        }
        return exe;
    }

    private static boolean isSet(Map<String, String> env, String name) {
        String v = env.get(name);
        return v != null && !v.isBlank();
    }

    private static int intOf(Map<String, String> env, String name, int def) {
        String v = env.get(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }
}
