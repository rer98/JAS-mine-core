package microsim.web.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Database file selection helpers for the JAS-mine Web DB Explorer.
 * Resolves input/output database directories, validates output timestamps, chooses conventional
 * or unambiguous database files, and constructs read-only H2 JDBC URLs.
 *
 * @author ross richardson
 *
 */

/** Utility methods for selecting JAS-mine input/output H2 database files. */
public final class DatabaseFileUtils {
    private DatabaseFileUtils() {}

    public static final class NoDatabaseFileException extends Exception {
        public NoDatabaseFileException(String message) { super(message); }
    }

    public static final class AmbiguousDatabaseFileException extends Exception {
        private final List<String> files;

        public AmbiguousDatabaseFileException(String message, List<String> files) {
            super(message);
            this.files = files;
        }

        public List<String> getFiles() { return files; }
    }

    public static File databaseDirectory(String role, String timestamp) throws IOException {
        if ("input".equals(role)) return new File("input");

        if ("output".equals(role)) {
            if (!PathSafety.isSafeTimestamp(timestamp)) {
                throw new IllegalArgumentException("Missing or invalid 'timestamp' for output database");
            }
            File tsDir = PathSafety.safeResolve("output", timestamp);
            if (tsDir == null) {
                throw new IllegalArgumentException("Invalid 'timestamp' for output database");
            }
            return new File(tsDir, "database");
        }

        throw new IllegalArgumentException("role must be 'input' or 'output'");
    }

    public static String preferredDatabaseName(String role) {
        if ("input".equals(role)) return "input.mv.db";
        if ("output".equals(role)) return "out.mv.db";
        throw new IllegalArgumentException("role must be 'input' or 'output'");
    }

    public static File selectDatabaseFile(File dir, String preferredName)
            throws NoDatabaseFileException, AmbiguousDatabaseFileException {
        File[] dbFiles = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".db"));
        if (dbFiles == null || dbFiles.length == 0) {
            throw new NoDatabaseFileException("No database file found in " + dir.getPath());
        }

        File preferred = new File(dir, preferredName);
        if (preferred.isFile()) return preferred;
        if (dbFiles.length == 1) return dbFiles[0];

        List<String> names = new ArrayList<>();
        for (File f : dbFiles) names.add(f.getName());
        Collections.sort(names);
        throw new AmbiguousDatabaseFileException(
            "Multiple database files found in " + dir.getPath() + " and expected " + preferredName + " is absent",
            names
        );
    }

    public static String h2JdbcUrl(File dir, File dbFile) {
        String filename = dbFile.getName();
        String base = filename.endsWith(".mv.db")
            ? filename.substring(0, filename.length() - 6)
            : filename.substring(0, filename.lastIndexOf(".db"));
        return "jdbc:h2:file:" + dir.getAbsolutePath() + "/" + base + ";ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0";
    }
}
