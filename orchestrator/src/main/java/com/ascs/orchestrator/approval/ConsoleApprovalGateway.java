package com.ascs.orchestrator.approval;

import com.ascs.orchestrator.model.ApprovalRequest;
import com.ascs.orchestrator.model.Decision;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Interactive approver: prompts on a terminal. Use for live demos (--interactive). */
public final class ConsoleApprovalGateway implements ApprovalGateway {

    private static final Object PROMPT_LOCK = new Object();
    private final BufferedReader in;
    private final PrintStream out;
    private final String approver;

    public ConsoleApprovalGateway() {
        // review:accepted CR-04 interactive approver: the terminal is its user interface
        this(System.in, System.out, System.getProperty("user.name", "console-user"));
    }

    public ConsoleApprovalGateway(InputStream in, PrintStream out, String approver) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        this.approver = approver;
    }

    @Override
    public void request(ApprovalRequest r, Consumer<Decision> callback) {
        Thread t = new Thread(() -> {
            synchronized (PROMPT_LOCK) {
                out.printf("%n=== APPROVAL REQUIRED: stage '%s' (impact %s) ===%n%s%n", r.stageId(), r.impact(), r.summary());
                r.proposedOutputs().forEach((n, c) -> out.printf("--- %s ---%n%s%n", n, c));
                r.warnings().forEach(w -> out.println("WARNING: " + w));
                out.print("Approve? [y/N] + optional comment: ");
                try {
                    String line = in.readLine();
                    boolean yes = line != null && (line.toLowerCase().startsWith("y"));
                    String comment = line == null ? "eof" : line.replaceFirst("(?i)^(y(es)?|n(o)?)\\s*", "");
                    callback.accept(yes ? Decision.approve(approver, comment) : Decision.reject(approver, comment));
                } catch (Exception e) {
                    callback.accept(Decision.reject(approver, "console error: " + e));
                }
            }
        }, "console-approval-" + r.stageId());
        t.setDaemon(true);
        t.start();
    }
}
