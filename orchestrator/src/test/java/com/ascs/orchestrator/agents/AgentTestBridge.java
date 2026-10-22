package com.ascs.orchestrator.agents;

import java.util.List;

/** Exposes package-private agent helpers to tests in other packages. */
public final class AgentTestBridge {
    private AgentTestBridge() {}

    public static String group(String path) {
        return DevelopmentAgent.group(path);
    }

    public static String ranges(List<Integer> lines) {
        return QaAgent.ranges(lines);
    }
}
