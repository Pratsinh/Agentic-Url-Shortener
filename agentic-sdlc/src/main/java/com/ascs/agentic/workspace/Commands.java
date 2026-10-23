package com.ascs.agentic.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs a fixed program with arguments (never a shell string, so nothing is interpreted), in a given directory,
 * with a timeout and a scrubbed environment: variables that look like credentials (the model API key among them)
 * are removed, so code an agent wrote and then executed through a build cannot read them.
 */
public final class Commands {

    public record Result(int exitCode, String output, long durationMs, boolean timedOut) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    static final Pattern SECRET_ENV = Pattern.compile("(?i).*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|AUTH).*");

    private Commands() {}

    public static Result run(List<String> command, Path dir, Duration timeout, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        Path out = Files.createTempFile("agentic-cmd", ".out");
        long start = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile())
                    .redirectErrorStream(true).redirectOutput(out.toFile());
            Map<String, String> env = pb.environment();
            env.keySet().removeIf(k -> SECRET_ENV.matcher(k).matches());
            env.putAll(extraEnv);
            Process p = pb.start();
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly().waitFor();
            }
            String text = Files.readString(out, StandardCharsets.UTF_8);
            return new Result(finished ? p.exitValue() : -1, text, (System.nanoTime() - start) / 1_000_000, !finished);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /** The scrubbed copy of an environment (exposed for tests and the doctor command). */
    public static Map<String, String> scrub(Map<String, String> env) {
        Map<String, String> copy = new HashMap<>(env);
        copy.keySet().removeIf(k -> SECRET_ENV.matcher(k).matches());
        return copy;
    }
}
