package com.ascs.orchestrator.model;

import java.util.Arrays;
import java.util.function.Function;

/** A named, side-effect-free check evaluated before a stage starts (entry) or before its outputs commit (exit). */
public interface Gate {

    String name();

    GateResult evaluate(GateContext ctx);

    static Gate of(String name, Function<GateContext, GateResult> fn) {
        return new Gate() {
            @Override public String name() { return name; }
            @Override public GateResult evaluate(GateContext ctx) { return fn.apply(ctx); }
        };
    }

    /** Entry gate: every named artifact must already exist in the store. */
    static Gate requireArtifacts(String... names) {
        return of("requires-artifacts" + Arrays.toString(names), ctx -> {
            for (String n : names) {
                if (ctx.store().latest(n).isEmpty()) return GateResult.fail("missing artifact '" + n + "'");
            }
            return GateResult.pass();
        });
    }

    /** Exit gate: every named output must be present and non-blank. */
    static Gate requireOutputs(String... names) {
        return of("requires-outputs" + Arrays.toString(names), ctx -> {
            for (String n : names) {
                String v = ctx.outputs().get(n);
                if (v == null || v.isBlank()) return GateResult.fail("missing or empty output '" + n + "'");
            }
            return GateResult.pass();
        });
    }
}
