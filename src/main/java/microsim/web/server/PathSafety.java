package microsim.web.server;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Safe filesystem path-resolution helpers for JAS-mine Web.
 * Provides canonical descendant checks used by input, output, export, and metadata endpoints
 * to prevent path traversal outside expected directories.
 *
 * @author ross richardson
 *
 */

/** Path validation helpers for user-supplied input/output file paths. */
public final class PathSafety {
    /** Pattern for valid export-timestamp directory names: digits, hyphens, underscores only. */
    private static final Pattern TIMESTAMP_RE = Pattern.compile("^[0-9_\\-]+$");

    private PathSafety() {}

    public static boolean isSafeTimestamp(String timestamp) {
        return timestamp != null && !timestamp.isEmpty() && TIMESTAMP_RE.matcher(timestamp).matches();
    }

    /**
     * Resolve {@code userPath} as a single path segment under {@code baseDir}, rejecting
     * traversal attempts. Returns {@code null} if the input contains path separators,
     * {@code ..}, or canonicalises outside {@code baseDir}.
     */
    public static File safeResolve(String baseDir, String userPath) throws IOException {
        if (userPath == null || userPath.isEmpty()
                || userPath.contains("/") || userPath.contains("\\") || userPath.contains("..")) {
            return null;
        }
        File base = new File(baseDir).getCanonicalFile();
        File resolved = new File(base, userPath).getCanonicalFile();
        return resolved.toPath().startsWith(base.toPath()) ? resolved : null;
    }

    /**
     * Resolve a relative path below an already-resolved base directory, allowing
     * subdirectories but rejecting absolute paths and traversal attempts.
     */
    public static File safeResolveDescendant(File baseDir, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty()
                || relativePath.startsWith("/") || relativePath.contains("\\")
                || relativePath.contains("..")) {
            return null;
        }
        File base = baseDir.getCanonicalFile();
        File resolved = new File(base, relativePath).getCanonicalFile();
        return resolved.toPath().startsWith(base.toPath()) ? resolved : null;
    }
}
