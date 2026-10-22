package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.domain.AuditEvent;
import com.ascs.shortener.domain.AuditEvent.Action;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryAuditTrailTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @Verifies({"AC-5.4"})
    void oldestEventsAreEvictedAtCapacityAndSequenceNumbersExposeTheGap() {
        InMemoryAuditTrail trail = new InMemoryAuditTrail(3);
        for (int i = 0; i < 5; i++) trail.record(T0, Action.LINK_REDIRECTED, "code" + i, "host=example.com");

        List<AuditEvent> kept = trail.recent(10);
        assertEquals(List.of(2L, 3L, 4L), kept.stream().map(AuditEvent::seq).toList());
        assertEquals(List.of(4L), trail.recent(1).stream().map(AuditEvent::seq).toList());
        assertTrue(trail.forCode("code0").isEmpty());
        assertEquals(1, trail.forCode("code4").size());
    }

    @Test
    void rejectsInvalidCapacityAndLimit() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryAuditTrail(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryAuditTrail(1).recent(0));
    }
}
