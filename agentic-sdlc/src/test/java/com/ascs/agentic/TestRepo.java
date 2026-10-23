package com.ascs.agentic;

import com.ascs.agentic.workspace.Commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A small real git repository shaped like the target project, for workspace and pipeline tests. */
public final class TestRepo {

    private TestRepo() {}

    public static Path create(Path dir) throws IOException, InterruptedException {
        write(dir, "README.md", "# test repo\n");
        write(dir, "CLAUDE.md", "Conventions: keep it simple.\n");
        write(dir, "pom.xml", "<project/>\n");
        write(dir, ".gitignore", ".agentic/\ntarget/\n");
        write(dir, ".github/workflows/ci.yml", "on: push\n");
        write(dir, "shortener-service/src/main/java/demo/Greeter.java", "package demo;\n\npublic class Greeter {\n    String greet() { return \"hi\"; }\n}\n");
        write(dir, "shortener-service/src/test/java/demo/GreeterTest.java", "package demo;\nclass GreeterTest {}\n");
        git(dir, "init", "-q");
        git(dir, "add", "-A");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        return dir;
    }

    public static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    public static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Commands.Result r = Commands.run(cmd, dir, Duration.ofSeconds(60), Map.of());
        if (!r.ok()) throw new IOException("git failed: " + r.output());
        return r.output();
    }
}
