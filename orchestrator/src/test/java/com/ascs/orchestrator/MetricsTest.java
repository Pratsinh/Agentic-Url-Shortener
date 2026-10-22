package com.ascs.orchestrator;

import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.RunOutcome;
import com.ascs.orchestrator.model.RunReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {

    @Test
    void rollsUpAcrossRuns() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Metrics m = new Metrics();
            RunReport ok = Fx.engine(Fx.graph(Fx.stage("a").build()), Map.of("a", Fx.ok("a")), pool).metrics(m).build().run("r1", "q");
            RunReport bad = Fx.engine(Fx.graph(Fx.stage("a").build()), Map.<String, Agent>of("a", Fx.boom("x")), pool).metrics(m).build().run("r2", "q");

            assertTrue(ok.success());
            assertEquals(RunOutcome.FAILED, bad.outcome());
            Metrics.Snapshot s = m.snapshot();
            assertEquals(2, s.runsCompleted());
            assertEquals(0.5, s.runSuccessRate());
            assertEquals(1, s.stagesSucceeded());
            assertEquals(1, s.stagesFailed());
            assertTrue(s.p95LatencyMs() >= s.p50LatencyMs());
            assertTrue(s.format().contains("run success rate=50%"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void emptyMetricsDoNotDivideByZero() {
        Metrics.Snapshot s = new Metrics().snapshot();
        assertEquals(0.0, s.runSuccessRate());
        assertEquals(0.0, s.retryRate());
        assertEquals(0.0, s.mttrMs());
        assertEquals(0, s.p95LatencyMs());
    }

    @Test
    void recoveryFeedsMttr() {
        Metrics m = new Metrics();
        m.recovery(Duration.ofMillis(100));
        m.recovery(Duration.ofMillis(300));
        assertEquals(200.0, m.snapshot().mttrMs());
    }
}
