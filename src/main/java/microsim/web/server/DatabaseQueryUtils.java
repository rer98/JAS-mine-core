/* (C) Copyright 2026, by Ross Richardson
 * Bounded analytical DB Explorer execution using a restricted H2 principal.
 * @author ross richardson
 */
package microsim.web.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;

public final class DatabaseQueryUtils {
    public static final int MAX_ROWS = 5000, MAX_SECONDS = 60, MAX_COLUMNS = 256;
    public static final int MAX_VALUE_BYTES = 64 * 1024, MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Semaphore QUERY_SLOT = new Semaphore(1);
    private static final ObjectMapper JSON = new ObjectMapper();
    private DatabaseQueryUtils() { }

    public static int rowLimit(int requested) { return requested <= 0 ? MAX_ROWS : Math.min(requested, MAX_ROWS); }
    public static int timeLimit(int requested) { return requested <= 0 ? MAX_SECONDS : Math.min(requested, MAX_SECONDS); }

    public static Map<String, Object> executeQuery(String url, String sql, int maxRows, int seconds) throws SQLException {
        return execute(url, null, sql, maxRows, seconds);
    }

    /** Trusted output setup: refresh table grants in the existing shared database. */
    public static Map<String, Object> executeOutputQuery(Path base, String sql, int maxRows, int seconds)
            throws SQLException, IOException {
        return execute(DatabaseQueryAccess.url(base, true), base, sql, maxRows, seconds);
    }

    private static Map<String, Object> execute(String url, Path output, String sql, int requestedRows, int requestedSeconds)
            throws SQLException {
        if (!QUERY_SLOT.tryAcquire()) throw new SQLException("Query already running", "JQ003");
        try {
            var query = SqlSafety.check(sql); // Every caller passes the same non-executing validation.
            if (output != null) {
                try { DatabaseQueryAccess.provisionOutputIfNeeded(output); }
                catch (IOException e) { throw new SQLException("Output query setup failed", "JQ001"); }
            }
            SqlSafety.checkCatalogue(query, DatabaseQueryAccess.inspect(url));
            int maxRows = rowLimit(requestedRows), seconds = timeLimit(requestedSeconds);
            List<String> columns = new ArrayList<>();
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            int bytes = 1024; // Reserve for response field names, limits and punctuation.
            try (var conn = DriverManager.getConnection(url, DatabaseQueryAccess.USER, DatabaseQueryAccess.PASSWORD)) {
                conn.setReadOnly(true);
                try (var stmt = conn.createStatement()) {
                    stmt.setMaxRows(maxRows + 1);
                    stmt.setQueryTimeout(seconds);
                    try (var rs = stmt.executeQuery(query.sql())) {
                        var metadata = rs.getMetaData();
                        int count = metadata.getColumnCount();
                        if (count > MAX_COLUMNS) throw new SQLException("Column limit exceeded", "JQ004");
                        for (int i = 1; i <= count; i++) {
                            String name = metadata.getColumnLabel(i);
                            bytes += encodedSize(name) + 1;
                            columns.add(name);
                            requireType(metadata.getColumnType(i), metadata.getPrecision(i));
                        }
                        if (bytes >= MAX_RESPONSE_BYTES) throw new SQLException("Metadata limit exceeded", "JQ004");
                        while (rs.next()) {
                            if (rows.size() >= maxRows) { truncated = true; break; }
                            List<Object> row = new ArrayList<>(count);
                            int rowBytes = 3;
                            for (int i = 1; i <= count; i++) {
                                Object value = readValue(rs, metadata.getColumnType(i), i);
                                rowBytes += encodedSize(value) + 1;
                                if (rowBytes + bytes > MAX_RESPONSE_BYTES) { truncated = true; break; }
                                row.add(value);
                            }
                            if (truncated) break;
                            bytes += rowBytes;
                            rows.add(row);
                        }
                    }
                }
            }
            return Map.of("columns", columns, "rows", rows, "maxRows", maxRows,
                    "timeoutSeconds", seconds, "truncated", truncated, "maxResponseBytes", MAX_RESPONSE_BYTES);
        } finally { QUERY_SLOT.release(); }
    }

    private static void requireType(int type, int precision) throws SQLException {
        switch (type) {
            case Types.NULL, Types.BOOLEAN, Types.BIT, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.REAL, Types.FLOAT, Types.DOUBLE, Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB,
                 Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> { }
            case Types.NUMERIC, Types.DECIMAL -> { }
            default -> throw new SQLException("Unsupported result type", "JQ005");
        }
    }

    private static Object readValue(ResultSet rs, int type, int column) throws SQLException {
        Object value;
        // Do not call getObject: JAVA_OBJECT can deserialize model-supplied classes.
        switch (type) {
            case Types.NULL: return null;
            case Types.BOOLEAN, Types.BIT: value = rs.getBoolean(column); break;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER: value = rs.getInt(column); break;
            case Types.BIGINT: value = rs.getLong(column); break;
            case Types.REAL, Types.FLOAT, Types.DOUBLE:
                double number = rs.getDouble(column);
                if (!Double.isFinite(number)) throw new SQLException("Non-finite result", "JQ006");
                value = number; break;
            case Types.NUMERIC, Types.DECIMAL:
                String numeric = readText(rs, column);
                try { value = numeric == null ? null : new java.math.BigDecimal(numeric); }
                catch (NumberFormatException e) { throw new SQLException("Cannot represent numeric result", "JQ006"); }
                break;
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE:
                value = rs.getString(column); break;
            default: value = readText(rs, column);
        }
        return rs.wasNull() ? null : value;
    }

    private static String readText(ResultSet rs, int column) throws SQLException {
        try (Reader reader = rs.getCharacterStream(column)) {
            if (reader == null) return null;
            StringBuilder text = new StringBuilder();
            char[] chunk = new char[2048];
            for (int n; (n = reader.read(chunk)) >= 0;) {
                if (text.length() + n > MAX_VALUE_BYTES) throw new SQLException("Value limit exceeded", "JQ004");
                text.append(chunk, 0, n);
            }
            return text.toString();
        } catch (IOException e) { throw new SQLException("Cannot read result", "JQ006"); }
    }

    private static int encodedSize(Object value) throws SQLException {
        try {
            int length = JSON.writeValueAsBytes(value).length;
            if (length > MAX_VALUE_BYTES) throw new SQLException("Value limit exceeded", "JQ004");
            return length;
        } catch (IOException e) { throw new SQLException("Cannot encode result", "JQ006"); }
    }

    public static String friendlySqlErrorMessage(SQLException e, String role) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        switch (state) {
            case "JQ001": return "Secure database queries require an updated image or query-account setup by the model developer.";
            case "JQ002": return "Database query privileges are not safely configured. Contact the model developer.";
            case "JQ003": return "Another database query is running. Wait for it to finish and retry.";
            case "JQ004": return "Query results exceed the supported size limits. Select fewer columns or aggregate the data.";
            case "JQ005": return "A selected column has an unsupported data type. Select scalar numeric, text or date columns.";
            case "JQ006": return "A query result cannot be read or represented. Check the selected values and conversions.";
        }
        if (state.startsWith("22")) return "A value cannot be converted or calculated as requested. Check the query's types and expressions.";
        return switch (e.getErrorCode()) {
            case 90048 -> "This database uses an unsupported H2 format. Ask the model developer to migrate it to the current H2 version.";
            case 90028, 90146 -> "Database file could not be found. Check that the selected database exists.";
            case 50200, 57014 -> "The query timed out or was cancelled. Reduce the amount of data queried and retry.";
            case 90096, 90040, 28000 -> "Database query access was denied. Contact the model developer.";
            default -> "The database could not run this query. Check the table and column names and supported SQL syntax.";
        };
    }
}
