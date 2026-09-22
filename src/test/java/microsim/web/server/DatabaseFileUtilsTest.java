package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for DatabaseFileUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by DatabaseFileUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class DatabaseFileUtilsTest {
    @Test
    public void databaseDirectoryValidatesRoleAndOutputTimestamp() throws Exception {
        assertEquals(new File("input"), DatabaseFileUtils.databaseDirectory("input", null));
        assertTrue(DatabaseFileUtils.databaseDirectory("output", "20260723").getPath().replace('\\', '/').endsWith("output/20260723/database"));

        assertBadDirectory("output", "../20260723", "Missing or invalid 'timestamp' for output database");
        assertBadDirectory("other", null, "role must be 'input' or 'output'");
    }

    @Test
    public void preferredDatabaseNameFollowsJasmineConventions() {
        assertEquals("input.mv.db", DatabaseFileUtils.preferredDatabaseName("input"));
        assertEquals("out.mv.db", DatabaseFileUtils.preferredDatabaseName("output"));
    }

    @Test
    public void selectDatabaseFilePrefersConventionalName() throws Exception {
        Path dir = Files.createTempDirectory("db-file-utils-preferred");
        Files.writeString(dir.resolve("input.mv.db"), "preferred", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("other.mv.db"), "other", StandardCharsets.UTF_8);

        File selected = DatabaseFileUtils.selectDatabaseFile(dir.toFile(), "input.mv.db");

        assertEquals("input.mv.db", selected.getName());
    }

    @Test
    public void selectDatabaseFileFallsBackWhenExactlyOneDbExists() throws Exception {
        Path dir = Files.createTempDirectory("db-file-utils-fallback");
        Files.writeString(dir.resolve("legacy.h2.db"), "legacy", StandardCharsets.UTF_8);

        File selected = DatabaseFileUtils.selectDatabaseFile(dir.toFile(), "input.mv.db");

        assertEquals("legacy.h2.db", selected.getName());
    }

    @Test
    public void selectDatabaseFileReportsMissingAndAmbiguousDatabases() throws Exception {
        Path empty = Files.createTempDirectory("db-file-utils-empty");
        try {
            DatabaseFileUtils.selectDatabaseFile(empty.toFile(), "input.mv.db");
            fail("Expected NoDatabaseFileException");
        } catch (DatabaseFileUtils.NoDatabaseFileException e) {
            assertEquals("No database file found in " + empty.toFile().getPath(), e.getMessage());
        }

        Path ambiguous = Files.createTempDirectory("db-file-utils-ambiguous");
        Files.writeString(ambiguous.resolve("b.mv.db"), "b", StandardCharsets.UTF_8);
        Files.writeString(ambiguous.resolve("a.mv.db"), "a", StandardCharsets.UTF_8);
        try {
            DatabaseFileUtils.selectDatabaseFile(ambiguous.toFile(), "input.mv.db");
            fail("Expected AmbiguousDatabaseFileException");
        } catch (DatabaseFileUtils.AmbiguousDatabaseFileException e) {
            assertEquals(List.of("a.mv.db", "b.mv.db"), e.getFiles());
            assertEquals("Multiple database files found in " + ambiguous.toFile().getPath() + " and expected input.mv.db is absent", e.getMessage());
        }
    }

    @Test
    public void h2JdbcUrlRequiresExistingLocalDatabaseAndKeepsLocking() throws Exception {
        File dir = Files.createTempDirectory("db-file-utils-url").toFile();
        File database = new File(dir, "input.mv.db");
        Files.writeString(database.toPath(), "fixture");
        String url = DatabaseFileUtils.h2JdbcUrl(dir, database);
        assertTrue(url.endsWith("/input;IFEXISTS=TRUE;ACCESS_MODE_DATA=r"));
        assertFalse(url.contains("FILE_LOCK=NO"));
        assertThrows(java.io.IOException.class, () -> DatabaseFileUtils.h2JdbcUrl(dir, new File(dir, "missing.mv.db")));
        assertThrows(java.io.IOException.class, () -> DatabaseFileUtils.h2JdbcUrl(dir, new File(dir, "legacy.h2.db")));
        File injected = new File(dir, "db;INIT=bad.mv.db");
        Files.writeString(injected.toPath(), "fixture");
        assertThrows(java.io.IOException.class, () -> DatabaseFileUtils.h2JdbcUrl(dir, injected));
    }

    private static void assertBadDirectory(String role, String timestamp, String message) throws Exception {
        try {
            DatabaseFileUtils.databaseDirectory(role, timestamp);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }
}
