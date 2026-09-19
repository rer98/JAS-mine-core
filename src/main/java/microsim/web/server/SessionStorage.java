package microsim.web.server;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/* (C) Copyright 2026, by Ross Richardson
 *
 * Opt-in session accounting and conservative cleanup. Never follows symbolic links.
 *
 * @author ross richardson
 *
 */
public final class SessionStorage {
    private final Path root;
    private final long allowance, warning, reserve;
    private final Set<Path> retired = new HashSet<>();
    private Map<String, Map<String, Stamp>> preview = Map.of();
    private String token;
    private long expires;
    private record Stamp(long size, long modified, Object key) {}

    public SessionStorage(Path root, long allowance, long warning, long reserve) {
        this.root = root.toAbsolutePath().normalize();
        if (allowance < 0 || reserve < 0 || warning < reserve || (allowance > 0 && warning >= allowance))
            throw new IllegalArgumentException("Invalid session storage thresholds");
        this.allowance = allowance; this.warning = warning; this.reserve = reserve;
    }
    public static SessionStorage fromEnvironment() {
        return new SessionStorage(Path.of("."), env("JASMINE_STORAGE_BYTES"),
            env("JASMINE_STORAGE_WARN_BYTES"), env("JASMINE_STORAGE_RESERVE_BYTES"));
    }
    private static long env(String name) { return Long.parseLong(System.getenv().getOrDefault(name, "0")); }
    public boolean enabled() { return allowance > 0; }
    public synchronized void retire(Path directory) {
        Path p = directory.toAbsolutePath().normalize();
        if (p.getParent() != null && p.getParent().equals(root.resolve("output"))) retired.add(p);
    }
    public Map<String, Object> status() throws IOException {
        if (!enabled()) return Map.of("enabled", false);
        long used = 0;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.toList()) {
                if (Files.isSymbolicLink(p)) continue;
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isRegularFile()) used = Math.addExact(used, a.size());
            }
        }
        long filesystemFree = Files.getFileStore(root).getUsableSpace();
        long remaining = Math.min(filesystemFree, Math.max(0, allowance - used));
        return Map.of("enabled", true, "usedBytes", used, "allowanceBytes", allowance,
            "filesystemFreeBytes", filesystemFree, "remainingBytes", remaining,
            "warning", remaining < warning, "buildBlocked", remaining < reserve,
            "quotaEnforcement", "Configured allowance; host quota enforcement is not verified");
    }
    public void requireBuildCapacity() throws IOException {
        if (Boolean.TRUE.equals(status().get("buildBlocked")))
            throw new IllegalStateException("Insufficient session storage for another build. Download files, then review cleanup, or start a new session.");
    }
    private boolean safe(Path p) {
        if (!p.startsWith(root.resolve("output"))) return false;
        for (Path q = p; q != null && q.startsWith(root); q = q.getParent())
            if (Files.isSymbolicLink(q)) return false;
        return true;
    }
    private Stamp stamp(Path p) throws IOException {
        var a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Stamp(a.size(), a.lastModifiedTime().toMillis(), a.fileKey());
    }
    private Map<String, Stamp> candidates(Path run, List<Map<String, Object>> retained) throws IOException {
        Map<String, Stamp> files = new TreeMap<>();
        if (!safe(run) || !Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)) return files;
        try (var paths = Files.walk(run)) {
            for (Path p : paths.sorted().toList()) {
                if (p.equals(run)) continue;
                String name = root.relativize(p).toString();
                List<String> reasons = microsim.data.StorageProtection.reasons(p);
                if (!safe(p)) reasons = List.of("Symbolic link: dependency not established");
                if (!reasons.isEmpty()) {
                    retained.add(Map.of("path", name, "reasons", reasons)); continue;
                }
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) files.put(name, stamp(p));
                else if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    retained.add(Map.of("path", name, "reasons", List.of("Unknown file type")));
            }
        }
        return files;
    }
    public synchronized Map<String, Object> preview() throws IOException {
        synchronized (microsim.data.StorageProtection.class) {
            Map<String, Map<String, Stamp>> snapshots = new TreeMap<>();
            List<Map<String, Object>> runs = new ArrayList<>();
            for (Path run : retired.stream().sorted().toList()) {
                if (!safe(run) || !Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)) continue;
                List<Map<String, Object>> retained = new ArrayList<>();
                Map<String, Stamp> files = candidates(run, retained);
                String name = root.relativize(run).toString();
                snapshots.put(name, files);
                runs.add(Map.of("path", name, "bytes", files.values().stream().mapToLong(Stamp::size).sum(),
                    "fileCount", files.size(), "retained", retained));
            }
            preview = snapshots; token = UUID.randomUUID().toString(); expires = System.currentTimeMillis() + 300_000;
            return Map.of("token", token, "runs", runs, "expiresInSeconds", 300);
        }
    }
    public synchronized Map<String, Object> delete(String suppliedToken, List<String> selected) throws IOException {
        synchronized (microsim.data.StorageProtection.class) {
            if (token == null || !token.equals(suppliedToken) || System.currentTimeMillis() > expires)
                throw new IllegalArgumentException("Cleanup preview expired. Review a new preview.");
            token = null;
            if (selected.isEmpty() || new HashSet<>(selected).size() != selected.size())
                throw new IllegalArgumentException("Select distinct runs from the preview");
            for (String name : selected) {
                Path run = root.resolve(name).normalize();
                if (!preview.containsKey(name) || !retired.contains(run) || !safe(run)
                        || !Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)
                        || !preview.get(name).equals(candidates(run, new ArrayList<>())))
                    throw new IllegalArgumentException("Files or protections changed. Review a new preview.");
            }
            long bytes = 0; List<String> deleted = new ArrayList<>(); List<String> removedRuns = new ArrayList<>();
            boolean complete = true;
            outer: for (String runName : selected) {
                for (var entry : preview.get(runName).entrySet()) {
                    Path p = root.resolve(entry.getKey());
                    if (!safe(p) || !microsim.data.StorageProtection.reasons(p).isEmpty()
                            || !entry.getValue().equals(stamp(p))) { complete = false; break outer; }
                    try { Files.delete(p); } catch (IOException e) { complete = false; break outer; }
                    deleted.add(entry.getKey()); bytes += entry.getValue().size();
                }
                Path run = root.resolve(runName);
                try (var paths = Files.walk(run)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                        if (safe(p) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                                && microsim.data.StorageProtection.reasons(p).isEmpty()
                                && !microsim.data.StorageProtection.protectsAncestor(p)) {
                            try { Files.delete(p); } catch (DirectoryNotEmptyException ignored) {}
                        }
                    }
                } catch (IOException e) { complete = false; }
                if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) { removedRuns.add(runName); retired.remove(run); }
            }
            return Map.of("deleted", deleted, "bytes", bytes, "complete", complete, "removedRuns", removedRuns);
        }
    }
}
