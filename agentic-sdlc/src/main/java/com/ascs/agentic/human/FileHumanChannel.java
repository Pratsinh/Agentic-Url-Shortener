package com.ascs.agentic.human;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Asynchronous, ticket-style human channel: each question is written to {@code <inbox>/<id>.request.md}; the run
 * waits until a person writes {@code <id>.response.txt}:
 * <pre>
 * decision: approve | reject | revise | answer
 * by: &lt;your name&gt;
 * &lt;comment, feedback or answers on the following lines&gt;
 * </pre>
 * The inbox lives under {@code .agentic/runs/<runId>/inbox}, outside the run's worktree, so no agent tool can
 * write a response. A processed response is renamed to {@code .done}.
 */
public final class FileHumanChannel implements HumanChannel {

    private static final System.Logger LOG = System.getLogger(FileHumanChannel.class.getName());

    private final Path inbox;
    private final Duration timeout;
    private final Duration poll;

    public FileHumanChannel(Path inbox, Duration timeout, Duration poll) {
        this.inbox = inbox;
        this.timeout = timeout;
        this.poll = poll;
    }

    @Override
    public Optional<Reply> ask(Question q) throws InterruptedException {
        Path request = inbox.resolve(q.id() + ".request.md");
        Path response = inbox.resolve(q.id() + ".response.txt");
        try {
            Files.createDirectories(inbox);
            Files.writeString(request, "# " + q.title() + "\n\nRun: " + q.runId() + "\n\n" + q.body() + "\n\n---\n"
                    + HumanChannel.instructions(q) + "\nWrite your response to: " + response.toAbsolutePath()
                    + "\n\n```\ndecision: approve\nby: your-name\noptional comment\n```\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        LOG.log(System.Logger.Level.INFO, "waiting for a human response at {0} (request: {1})", response.toAbsolutePath(), request.getFileName());
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(response)) {
                try {
                    Optional<Reply> r = parse(Files.readString(response, StandardCharsets.UTF_8), q, response.toString());
                    Files.move(response, inbox.resolve(q.id() + ".response.done"), StandardCopyOption.REPLACE_EXISTING);
                    if (r.isPresent()) return r;
                    Files.writeString(inbox.resolve(q.id() + ".response.error"), "unreadable response; expected 'decision:' and 'by:' lines\n");
                } catch (IOException e) {
                    // a half-written file: try again on the next poll
                }
            }
            Thread.sleep(poll.toMillis());
        }
        return Optional.empty();
    }

    static Optional<Reply> parse(String content, Question q, String channel) {
        List<String> lines = content.lines().toList();
        Verdict verdict = null;
        String by = null;
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            String l = line.strip();
            if (verdict == null && l.toLowerCase(Locale.ROOT).startsWith("decision:")) {
                try {
                    verdict = Verdict.valueOf(l.substring(9).strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            } else if (by == null && l.toLowerCase(Locale.ROOT).startsWith("by:")) {
                by = l.substring(3).strip();
            } else if (!l.isEmpty()) {
                text.append(line).append('\n');
            }
        }
        if (verdict == null || by == null || by.isEmpty() || !q.allowed().contains(verdict)) return Optional.empty();
        return Optional.of(new Reply(by, verdict, text.toString().strip(), "file:" + channel));
    }
}
