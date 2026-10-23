package com.ascs.orchestrator;

import com.ascs.orchestrator.audit.AuditLog;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The durability hook: every audit event reaches the sink in chain order, so a file copy verifies like the original. */
class AuditSinkTest {

    @Test
    void everyEventReachesTheSinkInChainOrder() {
        List<AuditLog.Event> copy = new ArrayList<>();
        AuditLog log = new AuditLog(Clock.systemUTC(), copy::add);

        log.append("r", null, AuditLog.Type.RUN_STARTED, "stages=1");
        log.append("r", "s", AuditLog.Type.LLM_CALL, "model=m tokens=1/2");

        assertEquals(log.events(), copy);
        assertTrue(AuditLog.verifyChain(copy), "the persisted copy verifies independently");
    }
}
