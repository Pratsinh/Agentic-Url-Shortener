package com.ascs.orchestrator.agents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs an external command (git) for an agent. An interface so tests can script the output. */
@FunctionalInterface
public interface CommandRunner {

    /** @return stdout lines; throws if the command cannot start, times out or exits non-zero */
    List<String> run(List<String> command) throws IOException, InterruptedException;

    /** Real processes started in {@code dir}, killed after 30 s. */
    static CommandRunner in(Path dir) {
        return in(dir, Duration.ofSeconds(30));
    }

    /**
     * Output goes to a temp file rather than a pipe: reading a pipe before waiting would hang on a stuck
     * process, and waiting before reading would deadlock once the output fills the pipe buffer.
     */
    static CommandRunner in(Path dir, Duration timeout) {
        return command -> {
            Path out = Files.createTempFile("agent-cmd", ".out");
            try {
                Process p = new ProcessBuilder(command).directory(dir.toFile())
                        .redirectErrorStream(true).redirectOutput(out.toFile()).start();
                if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly().waitFor();
                    throw new IOException(command + " timed out after " + timeout.toMillis() + " ms");
                }
                String text = Files.readString(out, StandardCharsets.UTF_8);
                if (p.exitValue() != 0) throw new IOException(command + " exited " + p.exitValue() + ": " + text.strip());
                return text.lines().toList();
            } finally {
                Files.deleteIfExists(out);
            }
        };
    }
}
