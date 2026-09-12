package microsim.web.server;

import java.io.File;
import java.io.IOException;
import java.util.Map;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Request parsing and target resolution helpers for DB Explorer queries.
 * Parses JSON request bodies, validates required role/sql fields, preserves output timestamps,
 * and resolves the database target before SQL execution.
 *
 * @author ross richardson
 *
 */

/** Parse DB Explorer requests and resolve their database target. */
public final class DatabaseRequestUtils {
    private DatabaseRequestUtils() {}

    public static final class QueryRequest {
        private final String role;
        private final String sql;
        private final String timestamp;

        public QueryRequest(String role, String sql, String timestamp) {
            this.role = role;
            this.sql = sql;
            this.timestamp = timestamp;
        }

        public String getRole() { return role; }
        public String getSql() { return sql; }
        public String getTimestamp() { return timestamp; }
    }

    public static final class DatabaseTarget {
        private final File dir;
        private final File dbFile;
        private final String jdbcUrl;

        public DatabaseTarget(File dir, File dbFile, String jdbcUrl) {
            this.dir = dir;
            this.dbFile = dbFile;
            this.jdbcUrl = jdbcUrl;
        }

        public File getDir() { return dir; }
        public File getDbFile() { return dbFile; }
        public String getJdbcUrl() { return jdbcUrl; }
    }

    public static QueryRequest parse(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("Missing 'role' or 'sql'");
        String role = asString(body.get("role"));
        String sql = asString(body.get("sql"));
        String timestamp = asString(body.get("timestamp"));
        if (role == null || sql == null) {
            throw new IllegalArgumentException("Missing 'role' or 'sql'");
        }
        return new QueryRequest(role, sql, timestamp);
    }

    public static DatabaseTarget resolveTarget(QueryRequest request)
            throws IOException, DatabaseFileUtils.NoDatabaseFileException, DatabaseFileUtils.AmbiguousDatabaseFileException {
        File dir = DatabaseFileUtils.databaseDirectory(request.getRole(), request.getTimestamp());
        File dbFile = DatabaseFileUtils.selectDatabaseFile(dir, DatabaseFileUtils.preferredDatabaseName(request.getRole()));
        return new DatabaseTarget(dir, dbFile, DatabaseFileUtils.h2JdbcUrl(dir, dbFile));
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
