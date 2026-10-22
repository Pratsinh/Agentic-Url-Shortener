package com.ascs.orchestrator.planner;

import com.ascs.orchestrator.planner.NormalizedRequirement.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for an LLM analyzer. Classifies the requirement, flags vague quality terms that lack
 * measurable criteria, records explicit assumptions, and emits a task decomposition for the detected kind.
 */
public final class RuleBasedRequirementAnalyzer implements RequirementAnalyzer {

    private record Vague(Pattern pattern, String term, String question, String assumption) {}

    private static final List<Vague> VAGUE = List.of(
            new Vague(Pattern.compile("\\bsecur(e|ity)\\b"), "secure",
                    "What does 'secure' cover: link-safety scanning, abuse/rate limiting, authentication, or data protection?",
                    "'Secure' = keep SSRF/internal-host blocking, add per-client rate limiting, and optional HTTPS-only targets"),
            new Vague(Pattern.compile("\\b(faster|fast|quick(er)?|performance)\\b"), "faster",
                    "What latency or throughput target defines 'faster', and at what load?",
                    "'Faster' = p99 redirect latency under 50 ms at 1,000 rps, served from a read-through cache"),
            new Vague(Pattern.compile("\\b(scalable|scale)\\b"), "scalable",
                    "Scale to what load and growth rate?",
                    "'Scalable' = 10k rps reads, 100 rps writes, horizontally scalable stateless service"),
            new Vague(Pattern.compile("\\b(better|improve[ds]?|robust|reliable|user-friendly)\\b"), "better",
                    "Which measurable outcome defines 'better'?",
                    "'Better' = 99.9% monthly availability and error rate under 0.1%"));

    private static final Pattern BROWNFIELD = Pattern.compile(
            "\\b(existing|replace|migrate|migration|refactor|enhance|upgrade|extend|fix|bug|swap|integrate)\\b");
    private static final Pattern HAS_NUMBER = Pattern.compile("\\d");

    @Override
    public NormalizedRequirement analyze(String requirement) {
        String text = requirement.toLowerCase();
        List<String> ambiguities = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        if (!HAS_NUMBER.matcher(text).find()) { // a number usually means the vague term is quantified
            for (Vague v : VAGUE) {
                if (v.pattern().matcher(text).find()) {
                    ambiguities.add(v.question());
                    assumptions.add(v.assumption());
                }
            }
        }
        if (!ambiguities.isEmpty()) {
            return new NormalizedRequirement(requirement, Kind.AMBIGUOUS,
                    "Quality goals are stated without measurable criteria; define them before designing.",
                    ambiguities, assumptions, ambiguousTasks());
        }
        if (BROWNFIELD.matcher(text).find()) {
            assumptions.addAll(List.of("Public HTTP API contract is unchanged (no client impact)",
                    "No data migration is required for the first release (cache and store start empty)",
                    "The change must be revertible without data loss"));
            return new NormalizedRequirement(requirement, Kind.BROWNFIELD,
                    "Modify the existing system with minimal blast radius: identify impacted modules, change them, prove no regressions.",
                    ambiguities, assumptions, brownfieldTasks());
        }
        assumptions.addAll(List.of("Redirects use HTTP 302 so every click is observable",
                "Short codes are 7-character random base62 values",
                "Prototype persistence may be in-memory behind a repository port"));
        return new NormalizedRequirement(requirement, Kind.GREENFIELD,
                "Build a new service from scratch: API, persistence port, analytics, validation and operational safeguards.",
                ambiguities, assumptions, greenfieldTasks());
    }

    // ---- task decompositions ---------------------------------------------------------------------------------

    private static List<Task> delivery(String designId) {
        return List.of(
                Task.of("implement-core", "Implement domain and service", "core.patch", designId).asCompensable(),
                Task.of("api-spec", "Define API contract", "openapi.yaml", designId),
                Task.of("unit-tests", "Unit tests", "unit-test-report.md", "implement-core").retries(2),
                Task.of("integration-tests", "Integration tests", "integration-test-report.md", "implement-core", "api-spec").retries(3),
                Task.of("security-review", "Security review", "security-report.md", "implement-core", "api-spec"),
                Task.of("documentation", "API docs and runbook", "docs.md", "implement-core", "api-spec")
                        .retries(2).withFallback("documentation-fallback"),
                Task.of("release-readiness", "Release readiness (join)", "release-checklist.md",
                        "unit-tests", "integration-tests", "security-review", "documentation").highImpact());
    }

    private static List<Task> greenfieldTasks() {
        List<Task> t = new ArrayList<>();
        t.add(Task.of("requirements", "Normalize requirements", "requirements.md"));
        t.add(Task.of("design", "Architecture and schema design", "design.md", "requirements"));
        t.addAll(delivery("design"));
        return t;
    }

    private static List<Task> ambiguousTasks() {
        List<Task> t = new ArrayList<>();
        t.add(Task.of("requirements", "Normalize requirements", "requirements.md"));
        t.add(Task.of("clarification", "Resolve ambiguities with a human", "clarifications.md", "requirements").highImpact());
        t.add(Task.of("design", "Architecture and schema design", "design.md", "clarification"));
        t.addAll(delivery("design"));
        return t;
    }

    private static List<Task> brownfieldTasks() {
        return List.of(
                Task.of("requirements", "Normalize requirements", "requirements.md"),
                Task.of("impact-analysis", "Codebase impact analysis", "impact-analysis.md", "requirements"),
                Task.of("design", "Change design", "design.md", "impact-analysis"),
                Task.of("implement-change", "Implement the change", "change.patch", "design")
                        .highImpact().retries(2).asCompensable(),
                Task.of("regression-tests", "Regression and integration tests", "regression-report.md", "implement-change")
                        .retries(2).rollbackRunOnFailure(),
                Task.of("security-review", "Security review", "security-report.md", "implement-change"),
                Task.of("documentation", "Docs and runbook", "docs.md", "design", "implement-change"),
                Task.of("release-readiness", "Release readiness (join)", "release-checklist.md",
                        "regression-tests", "security-review", "documentation").highImpact());
    }
}
