package com.ascs.agentic.human;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Interactive channel: the operator at the terminal is the approver. Questions are serialized (one prompt at a
 * time even when parallel stages ask together). Input: a first line with the decision (and optional comment);
 * for {@code answer} and {@code revise}, further lines until a line containing only {@code .}.
 *
 * <p>A decision must be a response to the question on screen: anything typed before the question appeared (e.g.
 * while agents were working) is discarded, never taken as an answer. Shortcuts where answers are allowed:
 * {@code accept} approves every proposed assumption, and a first line {@code AMB-n: ...} starts the answer directly.
 */
public final class ConsoleHumanChannel implements HumanChannel {

    private static final Pattern AMB_LINE = Pattern.compile("^\\s*(AMB-\\d+|ALL)\\s*[:=-].*", Pattern.CASE_INSENSITIVE);

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
        try {
            int stale = discardTypedAhead();
            out.printf("%n======== HUMAN INPUT REQUIRED: %s ========%n%s%n--------%n%s", q.title(), q.body(), HumanChannel.instructions(q));
            if (q.allowed().contains(Verdict.ANSWER)) out.println("- accept: approve every proposed assumption as is");
            if (stale > 0) out.println("(ignored " + stale + " line(s) typed before this question appeared)");
            while (true) {
                out.print("> decision: ");
                out.flush();
                String line = in.readLine();
                if (line == null) return Optional.empty();
                if (line.isBlank()) continue;
                boolean answers = q.allowed().contains(Verdict.ANSWER);
                if (answers && line.strip().equalsIgnoreCase("accept")) {
                    return Optional.of(new Reply(responder, Verdict.ANSWER, "ALL: accept", "console"));
                }
                if (answers && AMB_LINE.matcher(line).matches()) {
                    return Optional.of(new Reply(responder, Verdict.ANSWER, readUntilDot(new StringBuilder(line.strip())), "console"));
                }
                String[] parts = line.strip().split("\\s+", 2);
                Verdict v;
                try {
                    v = Verdict.valueOf(parts[0].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    out.println("unknown decision '" + parts[0] + "'. " + example(q));
                    continue;
                }
                if (!q.allowed().contains(v)) {
                    out.println("'" + parts[0] + "' is not allowed here. " + example(q));
                    continue;
                }
                StringBuilder text = new StringBuilder(parts.length > 1 ? parts[1] : "");
                if (v == Verdict.ANSWER || v == Verdict.REVISE) return Optional.of(new Reply(responder, v, readUntilDot(text), "console"));
                return Optional.of(new Reply(responder, v, text.toString().strip(), "console"));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private String readUntilDot(StringBuilder text) throws IOException {
        out.println("(one entry per line; finish with a line containing only '.')");
        for (String l = in.readLine(); l != null && !l.strip().equals("."); l = in.readLine()) {
            text.append(text.isEmpty() ? "" : "\n").append(l);
        }
        return text.toString().strip();
    }

    /** Drops lines already buffered or typed before the question is shown; returns how many were dropped. */
    private int discardTypedAhead() throws IOException {
        int n = 0;
        while (in.ready()) {
            if (in.readLine() == null) break;
            n++;
        }
        return n;
    }

    static String example(Question q) {
        if (q.allowed().contains(Verdict.ANSWER)) {
            return "Type 'accept' to approve all proposed assumptions, or answer each one, e.g.:\n"
                    + "  answer\n  AMB-1: accept\n  AMB-2: return 404 for expired links\n  .";
        }
        StringBuilder sb = new StringBuilder("Type one of:");
        for (Verdict v : q.allowed()) sb.append(' ').append(v.name().toLowerCase(Locale.ROOT));
        if (q.allowed().contains(Verdict.REVISE)) sb.append("  (revise: then your feedback lines, then '.')");
        return sb.toString();
    }
}
