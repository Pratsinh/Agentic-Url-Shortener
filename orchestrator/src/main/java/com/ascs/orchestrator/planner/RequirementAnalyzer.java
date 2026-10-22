package com.ascs.orchestrator.planner;

/** Port: turn free text into a {@link NormalizedRequirement}. An LLM-backed analyzer is a drop-in replacement. */
@FunctionalInterface
public interface RequirementAnalyzer {
    NormalizedRequirement analyze(String requirement);
}
