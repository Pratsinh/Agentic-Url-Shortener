package com.ascs.agentic.pipeline;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.llm.Redactor;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.ArtifactRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durable run state under {@code .agentic/runs/<runId>/}: the hash-chained audit log as JSON lines (appended as
 * events happen, so it survives a crash), every committed artifact version with its lineage, model-call
 * transcripts, and the final state, metrics and report. Content is redacted before it is written. Persistence
 * failures never stop the run but are counted and reported.
 */
public final class RunRecorder {

    private static final Pattern PRODUCED = Pattern.compile("^(\\S+)@(\\d+) ");
    private static final Pattern CHANGED = Pattern.compile("-> (\\S+)@(\\d+) ");

    private final Path dir;
    private final Redactor redactor;
    private final List<String> errors = new ArrayList<>();
    private ArtifactStore store;

    public RunRecorder(Path dir, Redactor redactor) throws IOException {
        this.dir = dir;
        this.redactor = redactor;
        Files.createDirectories(dir.resolve("artifacts"));
        Files.createDirectories(dir.resolve("llm").resolve("transcripts"));
    }

    public Path dir() {
        return dir;
    }

    public void attach(ArtifactStore store) {
        this.store = store;
    }

    /** Audit sink: called in chain order while the audit log is locked. */
    public synchronized void onAudit(AuditLog.Event e) {
        append("audit.jsonl", Json.compact(e));
        Matcher m = null;
        if (e.type() == AuditLog.Type.ARTIFACT_PRODUCED) m = PRODUCED.matcher(e.detail());
        if (e.type() == AuditLog.Type.CHANGE_APPLIED) m = CHANGED.matcher(e.detail());
        if (m != null && m.find() && store != null) {
            store.get(new ArtifactRef(m.group(1), Integer.parseInt(m.group(2)))).ifPresent(this::artifact);
        }
    }

    private void artifact(Artifact a) {
        String name = a.name();
        int dot = name.lastIndexOf('.');
        String file = (dot > 0 ? name.substring(0, dot) + ".v" + a.version() + name.substring(dot) : name + ".v" + a.version());
        write("artifacts/" + a.producerStage() + "/" + file, a.content());
        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("ref", a.ref().toString());
        idx.put("producer", a.producerStage());
        idx.put("sha256", a.contentHash());
        idx.put("inputs", a.inputs().stream().map(ArtifactRef::toString).toList());
        idx.put("createdAt", a.createdAt().toString());
        idx.put("file", "artifacts/" + a.producerStage() + "/" + file);
        append("artifacts/index.jsonl", Json.compact(idx));
    }

    public void transcript(String name, String json) {
        write("llm/transcripts/" + name, json);
    }

    public synchronized void write(String rel, String content) {
        try {
            Path p = dir.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.writeString(p, redactor.redact(content), StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add(rel + ": " + e.getMessage());
        }
    }

    private void append(String rel, String line) {
        try {
            Files.writeString(dir.resolve(rel), redactor.redact(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            errors.add(rel + ": " + e.getMessage());
        }
    }

    public synchronized List<String> errors() {
        return List.copyOf(errors);
    }

    /** Re-reads a persisted audit log and verifies its hash chain independently of the process that wrote it. */
    public static boolean verifyPersistedAudit(Path auditJsonl) throws IOException {
        List<AuditLog.Event> events = new ArrayList<>();
        for (String line : Files.readAllLines(auditJsonl, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) events.add(Json.read(line, AuditLog.Event.class));
        }
        return !events.isEmpty() && AuditLog.verifyChain(events);
    }
}
