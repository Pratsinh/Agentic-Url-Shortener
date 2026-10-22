package com.ascs.orchestrator.metrics;

import com.ascs.orchestrator.model.RunOutcome;
import com.ascs.orchestrator.policy.PolicyCategory;
import com.ascs.orchestrator.policy.Severity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reliability metrics, shareable across runs. Definitions:
 * <ul>
 *   <li>run success rate = SUCCEEDED runs / completed runs</li>
 *   <li>retry rate = retries / agent attempts; rollback rate = rolled-back runs / completed runs</li>
 *   <li>MTTR = mean time from a stage's first failed attempt to its eventual success (retry, fallback or re-run)</li>
 *   <li>latency = end-to-end wall time of a run (p50/p95 by nearest rank)</li>
 * </ul>
 */
public final class Metrics {

    private long runsStarted;
    private final Map<RunOutcome, Long> outcomes = new EnumMap<>(RunOutcome.class);
    private long attempts, retries, fallbacks, stagesSucceeded, stagesFailed, rollbacks, rollbackFailures, replans;
    private long approvalsRequested, approvalsGranted, approvalsRejected, approvalsTimedOut;
    private final Map<String, Long> violations = new TreeMap<>();
    private final List<Long> latenciesMs = new ArrayList<>();
    private final List<Long> recoveriesMs = new ArrayList<>();

    public synchronized void runStarted() { runsStarted++; }
    public synchronized void attempt() { attempts++; }
    public synchronized void retry() { retries++; }
    public synchronized void fallback() { fallbacks++; }
    public synchronized void stageSucceeded() { stagesSucceeded++; }
    public synchronized void stageFailed() { stagesFailed++; }
    public synchronized void rollback() { rollbacks++; }
    public synchronized void rollbackFailed() { rollbackFailures++; }
    public synchronized void replan() { replans++; }
    public synchronized void approvalRequested() { approvalsRequested++; }
    public synchronized void approvalGranted() { approvalsGranted++; }
    public synchronized void approvalRejected() { approvalsRejected++; }
    public synchronized void approvalTimedOut() { approvalsTimedOut++; }
    public synchronized void recovery(Duration d) { recoveriesMs.add(d.toMillis()); }

    public synchronized void policyViolation(PolicyCategory c, Severity s) {
        violations.merge(c + "/" + s, 1L, Long::sum);
    }

    public synchronized void runFinished(RunOutcome outcome, Duration latency) {
        latenciesMs.add(latency.toMillis());
        outcomes.merge(outcome, 1L, Long::sum);
    }

    public record Snapshot(long runsStarted, long runsCompleted, long runsSucceeded, long runsFailed,
                           long runsRolledBack, long runsRollbackIncomplete, long runsSafeStopped,
                           long attempts, long retries, long fallbacks, long stagesSucceeded, long stagesFailed,
                           long rollbacks, long rollbackFailures, long replans,
                           long approvalsRequested, long approvalsGranted, long approvalsRejected, long approvalsTimedOut,
                           Map<String, Long> policyViolations,
                           double runSuccessRate, double retryRate, double rollbackRate,
                           long recoveries, double mttrMs, long p50LatencyMs, long p95LatencyMs, double meanLatencyMs) {

        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("runs: started=%d completed=%d succeeded=%d failed=%d rolledBack=%d rollbackIncomplete=%d safeStopped=%d%n",
                    runsStarted, runsCompleted, runsSucceeded, runsFailed, runsRolledBack, runsRollbackIncomplete, runsSafeStopped));
            sb.append(String.format("run success rate=%.0f%%  rollback rate=%.0f%%%n", runSuccessRate * 100, rollbackRate * 100));
            sb.append(String.format("stage attempts=%d retries=%d (retry rate %.0f%%) fallbacks=%d stageSucceeded=%d stageFailed=%d%n",
                    attempts, retries, retryRate * 100, fallbacks, stagesSucceeded, stagesFailed));
            sb.append(String.format("rollbacks=%d rollbackFailures=%d replans=%d%n", rollbacks, rollbackFailures, replans));
            sb.append(String.format("approvals: requested=%d granted=%d rejected=%d timedOut=%d%n",
                    approvalsRequested, approvalsGranted, approvalsRejected, approvalsTimedOut));
            sb.append("policy violations: ").append(policyViolations.isEmpty() ? "none" : policyViolations).append('\n');
            sb.append(String.format("MTTR=%.0f ms over %d recoveries  latency: mean=%.0f ms p50=%d ms p95=%d ms",
                    mttrMs, recoveries, meanLatencyMs, p50LatencyMs, p95LatencyMs));
            return sb.toString();
        }
    }

    public synchronized Snapshot snapshot() {
        long completed = outcomes.values().stream().mapToLong(Long::longValue).sum();
        long runsSucceeded = runs(RunOutcome.SUCCEEDED), runsRolledBack = runs(RunOutcome.ROLLED_BACK);
        long runsRollbackIncomplete = runs(RunOutcome.ROLLBACK_INCOMPLETE);
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        return new Snapshot(runsStarted, completed, runsSucceeded, runs(RunOutcome.FAILED), runsRolledBack, runsRollbackIncomplete,
                runs(RunOutcome.SAFE_STOPPED), attempts, retries, fallbacks, stagesSucceeded, stagesFailed, rollbacks, rollbackFailures,
                replans, approvalsRequested, approvalsGranted, approvalsRejected, approvalsTimedOut,
                Map.copyOf(violations),
                ratio(runsSucceeded, completed), ratio(retries, attempts), ratio(runsRolledBack + runsRollbackIncomplete, completed),
                recoveriesMs.size(), mean(recoveriesMs), percentile(sorted, 50), percentile(sorted, 95), mean(latenciesMs));
    }

    private long runs(RunOutcome o) { return outcomes.getOrDefault(o, 0L); }

    private static double ratio(long a, long b) { return b == 0 ? 0.0 : (double) a / b; }

    private static double mean(List<Long> xs) {
        return xs.isEmpty() ? 0.0 : xs.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
