package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for DatabaseQueryUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by DatabaseQueryUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class DatabaseQueryUtilsTest {
    @Test
    public void executeQueryReturnsColumnsRowsAndMetadata() throws Exception {
        String jdbcUrl = createDatabase();

        Map<String, Object> res = DatabaseQueryUtils.executeQuery(jdbcUrl, "select id, name from people order by id", 10, 7);

        assertEquals(List.of("ID", "NAME"), res.get("columns"));
        assertEquals(List.of(List.of(1, "Alice"), List.of(2, "Bob"), List.of(3, "Carol")), res.get("rows"));
        assertEquals(10, res.get("maxRows"));
        assertEquals(7, res.get("timeoutSeconds"));
        assertEquals(false, res.get("truncated"));
    }

    @Test
    public void executeQueryTruncatesAtMaxRows() throws Exception {
        String jdbcUrl = createDatabase();

        Map<String, Object> res = DatabaseQueryUtils.executeQuery(jdbcUrl, "select id from people order by id", 2, 0);

        assertEquals(List.of("ID"), res.get("columns"));
        assertEquals(List.of(List.of(1), List.of(2)), res.get("rows"));
        assertEquals(2, res.get("maxRows"));
        assertEquals(0, res.get("timeoutSeconds"));
        assertEquals(true, res.get("truncated"));
    }

    @Test
    public void executeQueryCanDisableMaxRows() throws Exception {
        String jdbcUrl = createDatabase();

        Map<String, Object> res = DatabaseQueryUtils.executeQuery(jdbcUrl, "select id from people order by id", 0, 0);

        assertEquals(List.of(List.of(1), List.of(2), List.of(3)), res.get("rows"));
        assertEquals(false, res.get("truncated"));
    }

    @Test
    public void friendlySqlErrorMessagesPreserveExistingSpecialCases() {
        assertEquals(
            "Input database is in H2 1.x format and cannot be read by the current H2 2.x driver. Please migrate the input database to H2 2.x format (input.mv.db).",
            DatabaseQueryUtils.friendlySqlErrorMessage(new SQLException("boom 90048 old db"), "input")
        );
        assertEquals(
            "Database file could not be found. Please ensure an output database exists in the output directory.",
            DatabaseQueryUtils.friendlySqlErrorMessage(new SQLException("boom 90028 missing"), "output")
        );
        assertEquals("plain error", DatabaseQueryUtils.friendlySqlErrorMessage(new SQLException("plain error"), "input"));
    }

    private static String createDatabase() throws Exception {
        Path dir = Files.createTempDirectory("db-query-utils");
        String jdbcUrl = "jdbc:h2:file:" + dir.resolve("testdb").toAbsolutePath() + ";TRACE_LEVEL_FILE=0";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("create table people(id int primary key, name varchar(20))");
            stmt.execute("insert into people values(1, 'Alice'), (2, 'Bob'), (3, 'Carol')");
        }
        return jdbcUrl + ";ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0";
    }
}
