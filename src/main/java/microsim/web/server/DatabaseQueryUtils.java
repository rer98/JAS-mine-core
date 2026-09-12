package microsim.web.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/* (C) Copyright 2026, by Ross Richardson
 *
 * JDBC query execution helper for the JAS-mine Web DB Explorer.
 * Executes validated read-only SQL, applies row limits and timeouts, extracts result metadata,
 * and shapes query results and selected H2 errors for the browser.
 *
 * @author ross richardson
 *
 */

/** Execute read-only H2 database queries and shape DB Explorer JSON results. */
public final class DatabaseQueryUtils {
    private DatabaseQueryUtils() {}

    public static Map<String, Object> executeQuery(
            String jdbcUrl,
            String sql,
            int maxRows,
            int timeoutSeconds
    ) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean resultTruncated = false;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            conn.setReadOnly(true);
            try (Statement stmt = conn.createStatement()) {
                if (maxRows > 0) stmt.setMaxRows(maxRows + 1);
                if (timeoutSeconds > 0) stmt.setQueryTimeout(timeoutSeconds);

                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(meta.getColumnName(i));
                    }
                    while (rs.next()) {
                        if (maxRows > 0 && rows.size() >= maxRows) {
                            resultTruncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            }
        }

        return Map.of(
            "columns", columns,
            "rows", rows,
            "maxRows", maxRows,
            "timeoutSeconds", timeoutSeconds,
            "truncated", resultTruncated
        );
    }

    public static String friendlySqlErrorMessage(SQLException e, String role) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("90048")) {
            return "Input database is in H2 1.x format and cannot be read by the current H2 2.x driver. Please migrate the input database to H2 2.x format (input.mv.db).";
        }
        if (msg != null && msg.contains("90028")) {
            return "Database file could not be found. Please ensure an " + role + " database exists in the " + role + " directory.";
        }
        return msg;
    }
}
