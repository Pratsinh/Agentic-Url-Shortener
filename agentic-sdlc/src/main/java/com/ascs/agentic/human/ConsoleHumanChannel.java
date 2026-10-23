package com.ascs.agentic.human;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Interactive channel: the operator at the terminal is the approver. Questions are serialized (one prompt at a
 * time even when parallel stages ask together). Input: a first line with the decision (and optional comment);
 * for {@code answer} and {@code revise}, further lines until a line containing only {@code .}.
 */
public final class ConsoleHumanChannel implements HumanChannel {

    private final BufferedReader in;
    private final PrintStream out;
    private final String responder;

    public ConsoleHumanChannel(InputStream in, PrintStream out, String responder) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        this.responder = responder;
    }

    @Override
    public synchronized Optional<Reply> ask(Question q) {
        out.printf("%n======== HUMAN INPUT REQUIRED: %s ========%n%s%n--------%n%s", q.title(), q.body(), HumanChannel.instructions(q));
        try {
            while (true) {
                out.print("> decision: ");
                out.flush();
                String line = in.readLine();
                if (line == null) return Optional.empty();
                String[] parts = line.strip().split("\\s+", 2);
                Verdict v;
                try {
                    v = Verdict.valueOf(parts[0].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    out.println("unknown decision '" + parts[0] + "'");
                    continue;
                }
                if (!q.allowed().contains(v)) {
                    out.println("not allowed here: " + v);
                    continue;
                }
                StringBuilder text = new StringBuilder(parts.length > 1 ? parts[1] : "");
                if (v == Verdict.ANSWER || v == Verdict.REVISE) {
                    out.println("(enter text; finish with a line containing only '.')");
                    for (String l = in.readLine(); l != null && !l.strip().equals("."); l = in.readLine()) {
                        text.append(text.isEmpty() ? "" : "\n").append(l);
                    }
                }
                return Optional.of(new Reply(responder, v, text.toString().strip(), "console"));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
