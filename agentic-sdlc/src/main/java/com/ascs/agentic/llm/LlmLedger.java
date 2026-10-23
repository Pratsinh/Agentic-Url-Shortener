package com.ascs.agentic.llm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every model call of a run, with usage, latency and an estimated cost, plus the run's cost guardrail. Costs are
 * estimates from published per-token list prices (cache reads and writes priced separately); a model without a
 * known price contributes tokens but no cost, and the report says so.
 */
public final class LlmLedger {

    /** USD per million tokens: input, output, cache read, cache write (5-minute TTL). */
    record Price(double in, double out, double cacheRead, double cacheWrite) {}

    private static final Map<String, Price> PRICES = Map.of(
            "claude-opus-5-5", new Price(4.00, 20.00, 0.20, 5.00),
            "claude-sonnet-5-5", new Price(2.00, 10.00, 0.20, 2.50),
            "claude-haiku-5-5", new Price(0.10, 0.50, 0.01, 0.125),
            "claude-fable-5-1", new Price(10.00, 50.00, 0.25, 12.50));

    public record CallRecord(int seq, Instant at, String runId, String stageId, String role, String provider,
                             String requestedModel, String model, String requestId, String messageId, String stopReason,
                             Llm.Usage usage, long latencyMs, List<String> toolCalls, String error, Double estimatedCostUsd) {
        public CallRecord {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    public record Snapshot(int calls, int failedCalls, Llm.Usage usage, double estimatedCostUsd, boolean costComplete,
                           long p50LatencyMs, long p95LatencyMs, Map<String, Integer> callsByRole,
                           Map<String, Integer> callsByProvider, Map<String, Integer> callsByModel) {}

    private final double maxCostUsd;
    private final List<CallRecord> calls = new ArrayList<>();

    public LlmLedger(double maxCostUsd) {
        this.maxCostUsd = maxCostUsd;
    }

    public static Double estimateCost(String model, Llm.Usage u) {
        Price p = PRICES.get(normalize(model));
        if (p == null) return null;
        return (u.inputTokens() * p.in() + u.outputTokens() * p.out() + u.cacheReadTokens() * p.cacheRead()
                + u.cacheWriteTokens() * p.cacheWrite()) / 1_000_000.0;
    }

    /** The answering model may be reported as a dated/aliased id; match on the family prefix. */
    static String normalize(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return PRICES.keySet().stream().filter(m::startsWith).findFirst().orElse(m);
    }

    public synchronized int nextSeq() {
        return calls.size() + 1;
    }

    public synchronized void add(CallRecord r) {
        calls.add(r);
    }

    /** Refuses a new call once the run's estimated spend reached the cap (a hard stop, not retried). */
    public synchronized void ensureBudget() throws LlmException {
        double spent = snapshot().estimatedCostUsd();
        if (spent >= maxCostUsd) {
            throw new LlmException(String.format(Locale.ROOT,
                    "run cost guardrail: estimated spend $%.2f reached the cap of $%.2f (AGENTIC_MAX_RUN_USD)", spent, maxCostUsd), false);
        }
    }

    public synchronized List<CallRecord> calls() {
        return List.copyOf(calls);
    }

    public synchronized Snapshot snapshot() {
        Llm.Usage total = Llm.Usage.ZERO;
        double cost = 0;
        boolean complete = true;
        int failed = 0;
        List<Long> latencies = new ArrayList<>();
        Map<String, Integer> byRole = new TreeMap<>(), byProvider = new TreeMap<>(), byModel = new TreeMap<>();
        for (CallRecord c : calls) {
            if (c.error() != null) {
                failed++;
                continue;
            }
            total = total.plus(c.usage());
            if (c.estimatedCostUsd() == null) complete = false;
            else cost += c.estimatedCostUsd();
            latencies.add(c.latencyMs());
            byRole.merge(c.role(), 1, Integer::sum);
            byProvider.merge(c.provider(), 1, Integer::sum);
            byModel.merge(c.model(), 1, Integer::sum);
        }
        Collections.sort(latencies);
        return new Snapshot(calls.size(), failed, total, cost, complete, pct(latencies, 50), pct(latencies, 95),
                byRole, byProvider, byModel);
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
