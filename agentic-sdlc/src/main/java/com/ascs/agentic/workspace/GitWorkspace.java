package com.ascs.agentic.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Isolated execution: each run works in its own git worktree on branch {@code agentic/<runId>}, created from the
 * repository's current HEAD. Agents can change files only here; the main working tree and branch are never
 * touched. Promoting the change is a commit on the run branch, made only after human approval, and merging that
 * branch remains a human decision outside the system.
 *
 * <p>Only the fixed git invocations below exist; no caller (and in particular no model) supplies git arguments.
 */
public final class GitWorkspace {

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(2);

    private final Path repoRoot;
    private final Path root;
    private final String branch;
    private final String baseCommit;

    private GitWorkspace(Path repoRoot, Path root, String branch, String baseCommit) {
        this.repoRoot = repoRoot;
        this.root = root;
        this.branch = branch;
        this.baseCommit = baseCommit;
    }

    public static GitWorkspace create(Path repoRoot, Path worktreesDir, String runId) throws IOException, InterruptedException {
        if (!runId.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("invalid run id");
        String base = git(repoRoot, "rev-parse", "HEAD").strip();
        Files.createDirectories(worktreesDir);
        Path dir = worktreesDir.resolve(runId).toAbsolutePath().normalize();
        String branch = "agentic/" + runId;
        git(repoRoot, "worktree", "add", "-b", branch, dir.toString(), base);
        return new GitWorkspace(repoRoot, dir, branch, base);
    }

    /** Re-attaches to the worktree of an earlier run (resume / inspection). */
    public static GitWorkspace open(Path repoRoot, Path worktreesDir, String runId, String baseCommit) {
        return new GitWorkspace(repoRoot, worktreesDir.resolve(runId).toAbsolutePath().normalize(), "agentic/" + runId, baseCommit);
    }

    public Path root() { return root; }

    public String branch() { return branch; }

    public String baseCommit() { return baseCommit; }

    /** Unified diff of everything changed since the base commit (tracked, new and deleted files). */
    public synchronized String diff() throws IOException, InterruptedException {
        stageAll();
        return git(root, "diff", "--cached", "--no-color", "--no-ext-diff", baseCommit);
    }

    public synchronized String diffStat() throws IOException, InterruptedException {
        stageAll();
        return git(root, "diff", "--cached", "--stat", "--no-color", baseCommit);
    }

    public synchronized List<String> changedFiles() throws IOException, InterruptedException {
        stageAll();
        List<String> out = new ArrayList<>();
        for (String line : git(root, "diff", "--cached", "--name-only", baseCommit).split("\\R")) {
            if (!line.isBlank()) out.add(line.strip());
        }
        return out;
    }

    /** True when the path exists in the base commit (used to tell edits of existing code from new files). */
    public boolean existedAtBase(String relPath) throws IOException, InterruptedException {
        Commands.Result r = Commands.run(List.of("git", "cat-file", "-e", baseCommit + ":" + relPath), root, GIT_TIMEOUT, Map.of());
        return r.ok();
    }

    /** Commits all changes on the run branch; returns the new commit id. */
    public synchronized String commit(String message, String author, String email) throws IOException, InterruptedException {
        stageAll();
        git(root, "-c", "user.name=" + author, "-c", "user.email=" + email, "commit", "--no-verify", "-q", "-m", message);
        return git(root, "rev-parse", "HEAD").strip();
    }

    /** URL of a configured remote of the repository. */
    public String remoteUrl(String remote) throws IOException, InterruptedException {
        return git(repoRoot, "remote", "get-url", checkRemote(remote)).strip();
    }

    /**
     * Pushes the run branch (only it, to the same name) to a remote. With a token, HTTPS authentication uses an inline
     * credential helper that reads the token from an environment variable of the git process only: the token is never
     * on a command line, and any configured helper (which could prompt) is bypassed. Without one, git's own
     * configuration (SSH keys, credential manager) applies; prompts are disabled either way.
     */
    public synchronized void push(String remote, String token) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        if (token != null && !token.isBlank()) {
            cmd.addAll(List.of("-c", "credential.helper=",
                    "-c", "credential.helper=!f() { echo username=x-access-token; echo \"password=$" + PUSH_TOKEN_ENV + "\"; }; f"));
        }
        cmd.addAll(List.of("push", "-q", checkRemote(remote), branch + ":refs/heads/" + branch));
        Map<String, String> env = token == null || token.isBlank() ? Map.of("GIT_TERMINAL_PROMPT", "0")
                : Map.of("GIT_TERMINAL_PROMPT", "0", PUSH_TOKEN_ENV, token);
        run(repoRoot, env, cmd.toArray(String[]::new));
    }

    static final String PUSH_TOKEN_ENV = "AGENTIC_PUSH_TOKEN";

    private static String checkRemote(String remote) {
        if (remote == null || !remote.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("invalid remote name");
        return remote;
    }

    /** Compensation: discards every change and commit on the run branch (the base commit is restored). */
    public synchronized void resetToBase() throws IOException, InterruptedException {
        git(root, "reset", "--hard", "-q", baseCommit);
        git(root, "clean", "-fdq");
    }

    /** Removes the worktree directory; the run branch (and any approved commit on it) is kept. */
    public synchronized void removeWorktree() throws IOException, InterruptedException {
        git(repoRoot, "worktree", "remove", "--force", root.toString());
    }

    /** Removes the worktree and deletes the run branch. */
    public synchronized void discard() throws IOException, InterruptedException {
        if (Files.exists(root)) removeWorktree();
        git(repoRoot, "branch", "-D", branch);
    }

    private void stageAll() throws IOException, InterruptedException {
        git(root, "add", "-A");
    }

    static String git(Path dir, String... args) throws IOException, InterruptedException {
        return run(dir, Map.of("GIT_TERMINAL_PROMPT", "0"), args);
    }

    private static String run(Path dir, Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Commands.Result r = Commands.run(cmd, dir, GIT_TIMEOUT, env);
        if (!r.ok()) throw new IOException("git " + args[0] + " failed (exit " + r.exitCode() + "): " + r.output().strip());
        return r.output();
    }
}
