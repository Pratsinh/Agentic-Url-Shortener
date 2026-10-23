package com.ascs.agentic;

import com.ascs.agentic.human.ConsoleHumanChannel;
import com.ascs.agentic.human.HumanChannel;
import com.ascs.agentic.human.HumanChannel.Verdict;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleHumanChannelTest {

    /**
     * A terminal: {@code early} was typed before the question appeared (immediately available), {@code late} is typed
     * after it (only readable once the channel blocks for input, never counted as already available).
     */
    static final class Terminal extends InputStream {
        private final byte[] early, late;
        private int e, l;

        Terminal(String early, String late) {
            this.early = early.getBytes(StandardCharsets.UTF_8);
            this.late = late.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int available() {
            return early.length - e;
        }

        @Override
        public int read() {
            if (e < early.length) return early[e++];
            return l < late.length ? late[l++] : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) return 0;
            byte[] src = e < early.length ? early : late;
            int pos = e < early.length ? e : l;
            int n = Math.min(len, src.length - pos);
            if (n <= 0) return -1;
            System.arraycopy(src, pos, b, off, n);
            if (src == early) e += n; else l += n;
            return n;
        }
    }

    final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    HumanChannel.Reply ask(String early, String late, List<Verdict> allowed) {
        ConsoleHumanChannel ch = new ConsoleHumanChannel(new Terminal(early, late), new PrintStream(screen, true, StandardCharsets.UTF_8), "pat");
        return ch.ask(new HumanChannel.Question("r", "q-1", "Question", "body", allowed)).orElseThrow();
    }

    String screen() {
        return screen.toString(StandardCharsets.UTF_8);
    }

    @Test
    void inputTypedBeforeTheQuestionIsNeverTakenAsTheDecision() {
        HumanChannel.Reply r = ask("approve\napprove\n", "reject not ready\n", List.of(Verdict.APPROVE, Verdict.REJECT));
        assertEquals(Verdict.REJECT, r.verdict(), "the stale 'approve' lines must not approve anything");
        assertEquals("not ready", r.text());
        assertTrue(screen().contains("ignored 2 line(s) typed before this question appeared"), screen());
    }

    @Test
    void acceptApprovesEveryProposedAssumption() {
        HumanChannel.Reply r = ask("", "accept\n", List.of(Verdict.ANSWER, Verdict.REJECT));
        assertEquals(Verdict.ANSWER, r.verdict());
        assertEquals("ALL: accept", r.text());
        assertEquals("pat", r.responder());
    }

    @Test
    void answersCanStartDirectlyOrAfterTheAnswerKeyword() {
        assertEquals("AMB-1:return 200\nAMB-2: accept", ask("", "AMB-1:return 200\nAMB-2: accept\n.\n", List.of(Verdict.ANSWER)).text());
        assertEquals("AMB-1: accept", ask("", "answer\nAMB-1: accept\n.\n", List.of(Verdict.ANSWER)).text());
    }

    @Test
    void invalidInputShowsAConcreteExampleAndAsksAgain() {
        HumanChannel.Reply r = ask("", "looks good\n\napprove\n", List.of(Verdict.APPROVE, Verdict.REJECT, Verdict.REVISE));
        assertEquals(Verdict.APPROVE, r.verdict());
        assertTrue(screen().contains("unknown decision 'looks'. Type one of: approve reject revise"), screen());

        HumanChannel.Reply a = ask("", "approve\nAMB-1: accept\n.\n", List.of(Verdict.ANSWER, Verdict.REJECT));
        assertEquals("AMB-1: accept", a.text());
        assertTrue(screen().contains("'approve' is not allowed here. Type 'accept'"), screen());
    }

    @Test
    void endOfInputMeansNoDecision() {
        ConsoleHumanChannel ch = new ConsoleHumanChannel(new Terminal("", ""), new PrintStream(screen, true, StandardCharsets.UTF_8), "pat");
        assertTrue(ch.ask(new HumanChannel.Question("r", "q", "t", "b", List.of(Verdict.APPROVE))).isEmpty(), "fail closed");
    }
}
