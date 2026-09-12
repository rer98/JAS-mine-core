package microsim.web.server;

import java.io.File;
import java.util.Locale;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Safe output-run file resolver for JAS-mine Web export endpoints.
 * Validates output timestamps, resolves files under output/<timestamp>, strips optional timestamp prefixes,
 * and enforces CSV/TSV restrictions for tabular endpoints.
 *
 * @author ross richardson
 *
 */

/** Safe resolution of files under output/<timestamp>. */
public final class OutputFileResolver {
    private OutputFileResolver() {}

    public record Result(File runDir, File file) {}

    public static File runDirectory(String timestamp) throws java.io.IOException {
        if (!PathSafety.isSafeTimestamp(timestamp)) return null;
        File runDir = PathSafety.safeResolve("output", timestamp);
        return runDir != null && runDir.exists() && runDir.isDirectory() ? runDir : null;
    }

    public static String stripTimestampPrefix(String timestamp, String path) {
        if (timestamp != null && path != null && path.startsWith(timestamp + "/")) {
            return path.substring(timestamp.length() + 1);
        }
        return path;
    }

    public static Result outputFile(String timestamp, String path) throws java.io.IOException {
        File runDir = runDirectory(timestamp);
        if (runDir == null) return new Result(null, null);
        File file = PathSafety.safeResolveDescendant(runDir, stripTimestampPrefix(timestamp, path));
        if (file == null || !file.exists() || !file.isFile()) return new Result(runDir, null);
        return new Result(runDir, file);
    }

    public static File tabularFile(String timestamp, String path) throws java.io.IOException {
        Result resolved = outputFile(timestamp, path);
        File file = resolved.file();
        if (file == null) return null;
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return (lower.endsWith(".csv") || lower.endsWith(".tsv")) ? file : null;
    }
}
