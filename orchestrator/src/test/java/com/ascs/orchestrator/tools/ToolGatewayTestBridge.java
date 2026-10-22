package com.ascs.orchestrator.tools;

/** Exposes package-private gateway helpers to tests in other packages. */
public final class ToolGatewayTestBridge {
    private ToolGatewayTestBridge() {}

    public static String correlationId(ToolGateway.Context ctx, int n) {
        return ToolGateway.correlationId(ctx, n);
    }
}
