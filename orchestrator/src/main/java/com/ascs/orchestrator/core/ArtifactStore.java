package com.ascs.orchestrator.core;

import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.ArtifactRef;
import com.ascs.orchestrator.util.Hashing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Thread-safe, append-only, versioned store: the cross-stage context and the basis of decision lineage. */
public final class ArtifactStore {

    private final Map<String, List<Artifact>> versions = new LinkedHashMap<>();
    private final Map<ArtifactRef, String> revoked = new java.util.HashMap<>();

    public synchronized Artifact put(String producerStage, String name, String content,
                                     List<ArtifactRef> inputs, Instant now) {
        List<Artifact> history = versions.computeIfAbsent(name, k -> new ArrayList<>());
        Artifact a = new Artifact(name, history.size() + 1, content, producerStage, inputs,
                Hashing.sha256(content), now);
        history.add(a);
        return a;
    }

    /** Latest version that has not been revoked. Revoked versions stay in history for audit/lineage. */
    public synchronized Optional<Artifact> latest(String name) {
        List<Artifact> h = versions.get(name);
        if (h == null) return Optional.empty();
        for (int i = h.size() - 1; i >= 0; i--) {
            if (!revoked.containsKey(h.get(i).ref())) return Optional.of(h.get(i));
        }
        return Optional.empty();
    }

    /** Marks a version invalid (stale or rolled back) without deleting it. */
    public synchronized void revoke(ArtifactRef ref, String reason) {
        if (get(ref).isPresent()) revoked.putIfAbsent(ref, reason);
    }

    public synchronized boolean isRevoked(ArtifactRef ref) {
        return revoked.containsKey(ref);
    }

    public synchronized Optional<Artifact> get(ArtifactRef ref) {
        List<Artifact> h = versions.get(ref.name());
        if (h == null || ref.version() < 1 || ref.version() > h.size()) return Optional.empty();
        return Optional.of(h.get(ref.version() - 1));
    }

    public synchronized List<Artifact> history(String name) {
        return List.copyOf(versions.getOrDefault(name, List.of()));
    }

    /** Latest non-revoked version of every artifact, ordered by name for determinism. */
    public synchronized List<Artifact> latestAll() {
        Map<String, Artifact> out = new TreeMap<>();
        for (String name : versions.keySet()) latest(name).ifPresent(a -> out.put(name, a));
        return List.copyOf(out.values());
    }

    /** All transitive ancestors of {@code ref} followed by the artifact itself (ancestors first). */
    public synchronized List<Artifact> lineage(ArtifactRef ref) {
        List<Artifact> out = new ArrayList<>();
        walk(ref, new HashSet<ArtifactRef>(), out);
        return out;
    }

    private void walk(ArtifactRef ref, Set<ArtifactRef> seen, List<Artifact> out) {
        if (!seen.add(ref)) return;
        Optional<Artifact> a = get(ref);
        if (a.isEmpty()) return;
        for (ArtifactRef in : a.get().inputs()) walk(in, seen, out);
        out.add(a.get());
    }
}
