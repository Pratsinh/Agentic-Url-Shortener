package com.ascs.agentic.tools;

import java.util.List;
import java.util.Set;

/**
 * What one stage may write, derived from the human-approved plan (least privilege).
 *
 * @param files       existing or new files the approved task lists: may be created or edited
 * @param newFileDirs directories in which the task may create <em>new</em> files (directories of its listed files)
 * @param prefixes    directory prefixes fully writable by this stage (test trees, the docs folder)
 */
public record WriteScope(Set<String> files, Set<String> newFileDirs, List<String> prefixes) {

    public WriteScope {
        files = Set.copyOf(files);
        newFileDirs = Set.copyOf(newFileDirs);
        prefixes = List.copyOf(prefixes);
    }

    /** @param existedAtBase whether the file is part of the base commit (new files get more latitude) */
    public boolean allows(String path, boolean existedAtBase) {
        if (files.contains(path)) return true;
        if (prefixes.stream().anyMatch(path::startsWith)) return true;
        int slash = path.lastIndexOf('/');
        return !existedAtBase && slash > 0 && newFileDirs.contains(path.substring(0, slash));
    }

    public WriteScope union(WriteScope other) {
        java.util.Set<String> f = new java.util.TreeSet<>(files);
        f.addAll(other.files);
        java.util.Set<String> d = new java.util.TreeSet<>(newFileDirs);
        d.addAll(other.newFileDirs);
        java.util.Set<String> p = new java.util.TreeSet<>(prefixes);
        p.addAll(other.prefixes);
        return new WriteScope(f, d, List.copyOf(p));
    }

    public String describe() {
        return "files=" + new java.util.TreeSet<>(files) + " newFilesIn=" + new java.util.TreeSet<>(newFileDirs)
                + " prefixes=" + prefixes;
    }
}
