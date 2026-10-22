package com.ascs.orchestrator.model;

import java.util.List;
import java.util.Map;

/** Everything a reviewer needs: what is proposed (not yet committed), its impact, and any non-blocking warnings. */
public record ApprovalRequest(String runId, String stageId, Impact impact, String summary,
                              Map<String, String> proposedOutputs, List<String> warnings) {
    public ApprovalRequest {
        proposedOutputs = Map.copyOf(proposedOutputs);
        warnings = List.copyOf(warnings);
    }
}
