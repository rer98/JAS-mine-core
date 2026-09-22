package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.Comparator;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for DatabaseRequestUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by DatabaseRequestUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class DatabaseRequestUtilsTest {
    @Test
    public void parseRequiresRoleAndSqlAndKeepsTimestamp() {
        DatabaseRequestUtils.QueryRequest req = DatabaseRequestUtils.parse(Map.of(
            "role", "output",
            "timestamp", "20260723",
            "sql", "select 1"
        ));

        assertEquals("output", req.getRole());
        assertEquals("20260723", req.getTimestamp());
        assertEquals("select 1", req.getSql());

        assertBadParse(null);
        assertBadParse(Map.of("role", "input"));
        assertBadParse(Map.of("sql", "select 1"));
        assertBadParse(Map.of("role", 123, "sql", "select 1"));
    }

    @Test
    public void resolveTargetSelectsOutputDatabaseAndBuildsJdbcUrl() throws Exception {
        String timestamp = "20990101_000001";
        Path dbDir = Paths.get("output", timestamp, "database");
        deleteTree(Paths.get("output", timestamp));
        Files.createDirectories(dbDir);
        Files.writeString(dbDir.resolve("out.mv.db"), "db", StandardCharsets.UTF_8);

        try {
            DatabaseRequestUtils.QueryRequest req = new DatabaseRequestUtils.QueryRequest("output", "select 1", timestamp);
            DatabaseRequestUtils.DatabaseTarget target = DatabaseRequestUtils.resolveTarget(req);

            assertEquals("out.mv.db", target.getDbFile().getName());
            assertTrue(target.getDir().getPath().replace('\\', '/').endsWith("output/" + timestamp + "/database"));
            assertTrue(target.getJdbcUrl().contains("/out;IFEXISTS=TRUE;ACCESS_MODE_DATA=r"));
        } finally {
            deleteTree(Paths.get("output", timestamp));
        }
    }

    @Test
    public void resolveTargetSurfacesValidationAndMissingDatabaseErrors() throws Exception {
        try {
            DatabaseRequestUtils.resolveTarget(new DatabaseRequestUtils.QueryRequest("other", "select 1", null));
            fail("Expected bad role");
        } catch (IllegalArgumentException e) {
            assertEquals("role must be 'input' or 'output'", e.getMessage());
        }

        String timestamp = "20990102_000002";
        Path dbDir = Paths.get("output", timestamp, "database");
        deleteTree(Paths.get("output", timestamp));
        Files.createDirectories(dbDir);
        try {
            DatabaseRequestUtils.resolveTarget(new DatabaseRequestUtils.QueryRequest("output", "select 1", timestamp));
            fail("Expected missing DB");
        } catch (DatabaseFileUtils.NoDatabaseFileException e) {
            assertTrue(e.getMessage().contains("No database file found"));
        } finally {
            deleteTree(Paths.get("output", timestamp));
        }
    }

    private static void assertBadParse(Map<String, Object> body) {
        try {
            DatabaseRequestUtils.parse(body);
            fail("Expected bad request");
        } catch (IllegalArgumentException e) {
            assertEquals("Missing 'role' or 'sql'", e.getMessage());
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }
}
