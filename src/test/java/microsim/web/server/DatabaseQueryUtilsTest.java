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
        assertEquals(60, res.get("timeoutSeconds"));
        assertEquals(true, res.get("truncated"));
    }

    @Test
    public void executeQueryCannotDisableSafetyLimits() throws Exception {
        String jdbcUrl = createDatabase();

        Map<String, Object> res = DatabaseQueryUtils.executeQuery(jdbcUrl, "select id from people order by id", 0, 0);

        assertEquals(List.of(List.of(1), List.of(2), List.of(3)), res.get("rows"));
        assertEquals(false, res.get("truncated"));
        assertEquals(5000, res.get("maxRows"));
        assertEquals(60, res.get("timeoutSeconds"));
    }

    @Test
    public void errorsNeverEchoQueryOrPrivateValues() {
        String secret = "PRIVATE_RECORD_VALUE";
        for (SQLException error : List.of(new SQLException(secret), new SQLException(secret, "22018", 22018),
                new SQLException(secret, "42000", 42000), new SQLException(secret, "90048", 90048))) {
            assertFalse(DatabaseQueryUtils.friendlySqlErrorMessage(error, "input").contains(secret));
        }
        assertTrue(DatabaseQueryUtils.friendlySqlErrorMessage(new SQLException(secret, "JQ001"), "input")
                .contains("updated image"));
    }

    private static String createDatabase() throws Exception {
        Path dir = Files.createTempDirectory("db-query-utils");
        String jdbcUrl = "jdbc:h2:file:" + dir.resolve("testdb").toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("create table people(id int primary key, name varchar(20))");
            stmt.execute("insert into people values(1, 'Alice'), (2, 'Bob'), (3, 'Carol')");
            DatabaseQueryAccess.provision(conn);
        }
        return jdbcUrl + ";ACCESS_MODE_DATA=r";
    }
}
