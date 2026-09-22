/* (C) Copyright 2026, by Ross Richardson
 * Security and lifecycle tests for restricted queries against actual H2 files.
 * @author ross richardson
 */
package microsim.web.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseQuerySecurityTest {
    @TempDir Path root;
    Path base() { return root.resolve("db"); }
    Connection admin() throws SQLException { return DriverManager.getConnection("jdbc:h2:file:" + base(), "sa", ""); }
    String url() throws Exception { return DatabaseQueryAccess.url(base(), true); }
    void prepared() throws Exception {
        try (var conn = admin(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE RESULTS(RUN_ID INT, ID INT, AMOUNT DOUBLE)");
            stmt.execute("INSERT INTO RESULTS VALUES (1, 1, 10), (2, 1, 12)");
            DatabaseQueryAccess.provision(conn);
        }
    }
    Map<String,Object> query(String sql) throws Exception { return DatabaseQueryUtils.executeQuery(url(), sql, 5000, 60); }

    @Test void packagedInputIsReadOnlyAndAccountCannotWrite() throws Exception {
        prepared();
        Path db = root.resolve("db.mv.db");
        byte[] before = Files.readAllBytes(db);
        var modified = Files.getLastModifiedTime(db);
        assertEquals(List.of(List.of(2L)), query("SELECT COUNT(*) FROM RESULTS").get("rows"));
        try (var reader = DriverManager.getConnection(url(), DatabaseQueryAccess.USER, DatabaseQueryAccess.PASSWORD);
             var stmt = reader.createStatement()) {
            assertThrows(SQLException.class, () -> stmt.execute("DELETE FROM RESULTS"));
            assertThrows(SQLException.class, () -> stmt.execute("SELECT FILE_READ('/tmp/probe')"));
        }
        DatabaseQueryUtils.executeOutputQuery(base(), "SELECT COUNT(*) FROM RESULTS", 5000, 60);
        assertArrayEquals(before, Files.readAllBytes(db));
        assertEquals(modified, Files.getLastModifiedTime(db));
    }

    @Test void sameSharedOutputSurvivesClosedAndLiveConnectionsAndNewTables() throws Exception {
        prepared();
        try (var model = admin(); var stmt = model.createStatement()) {
            stmt.execute("INSERT INTO RESULTS VALUES (3,1,14)");
            stmt.execute("CREATE TABLE NEW_RESULTS(ID INT)");
            stmt.execute("INSERT INTO NEW_RESULTS VALUES(42)");
            var result = DatabaseQueryUtils.executeOutputQuery(base(),
                "SELECT b.AMOUNT-a.AMOUNT AS difference FROM RESULTS a JOIN RESULTS b ON a.ID=b.ID WHERE a.RUN_ID=1 AND b.RUN_ID=3", 5000, 60);
            assertEquals(List.of(List.of(4.0)), result.get("rows"));
            assertEquals(List.of(List.of(42)), query("SELECT ID FROM NEW_RESULTS").get("rows"));
        }
        assertEquals(List.of(List.of(3L)), query("SELECT COUNT(*) FROM RESULTS").get("rows"));
    }

    @Test void missingOrOverprivilegedAccountNeverFallsBackToAdmin() throws Exception {
        try (var conn = admin(); var stmt = conn.createStatement()) { stmt.execute("CREATE TABLE T(ID INT)"); }
        assertEquals("JQ001", assertThrows(SQLException.class, () -> query("SELECT * FROM T")).getSQLState());
        DatabaseQueryAccess.provision(base());
        try (var conn = admin(); var stmt = conn.createStatement()) { stmt.execute("GRANT DELETE ON T TO PUBLIC"); }
        assertEquals("JQ002", assertThrows(SQLException.class, () -> query("SELECT * FROM T")).getSQLState());
    }

    @Test void fileFunctionsAliasesAndViewsAreNotExecuted() throws Exception {
        prepared();
        Path marker = root.resolve("written.txt");
        try (var conn = admin(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE ALIAS SECRET_READER FOR 'java.lang.System.getProperty'");
            stmt.execute("CREATE VIEW EXPOSED AS SELECT SECRET_READER('user.home') AS SECRET");
        }
        for (String sql : List.of("SELECT FILE_WRITE('data', '" + marker + "')", "SELECT SECRET_READER('user.home')",
                "SELECT * FROM EXPOSED", "WITH EXPOSED AS (SELECT * FROM EXPOSED) SELECT * FROM EXPOSED",
                "SELECT * FROM CSVREAD('" + marker + "')"))
            assertThrows(IllegalArgumentException.class, () -> query(sql), sql);
        assertFalse(Files.exists(marker));
    }

    @Test void unsafeResultTypesAndOversizedLobsAreRejectedWithoutObjectDeserialization() throws Exception {
        prepared();
        try (var conn = admin(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE SPECIAL(ID INT, DATA JAVA_OBJECT, BODY CLOB)");
            stmt.execute("INSERT INTO SPECIAL VALUES(1, NULL, SPACE(70000))");
            DatabaseQueryAccess.provision(conn);
        }
        assertEquals("JQ005", assertThrows(SQLException.class, () -> query("SELECT DATA FROM SPECIAL")).getSQLState());
        assertEquals("JQ004", assertThrows(SQLException.class, () -> query("SELECT BODY FROM SPECIAL")).getSQLState());
        assertEquals(List.of(List.of(1)), query("SELECT ID FROM SPECIAL").get("rows"));
    }

    @Test void boundsRowsColumnsEncodedValuesAndWholeResponse() throws Exception {
        prepared();
        try (var conn = admin(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE MANY_ROWS AS SELECT X AS ID FROM SYSTEM_RANGE(1, 6000)");
            stmt.execute("CREATE TABLE LARGE_ROWS AS SELECT SPACE(40000) AS BODY FROM SYSTEM_RANGE(1, 200)");
            DatabaseQueryAccess.provision(conn);
        }
        var rows = query("SELECT ID FROM MANY_ROWS");
        assertEquals(5000, ((List<?>)rows.get("rows")).size());
        assertEquals(true, rows.get("truncated"));
        var bytes = query("SELECT BODY FROM LARGE_ROWS");
        assertEquals(true, bytes.get("truncated"));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(bytes).length <= DatabaseQueryUtils.MAX_RESPONSE_BYTES);
        assertTrue(((List<?>)bytes.get("rows")).size() > 0);
        assertThrows(SQLException.class, () -> query("SELECT " + String.join(",", Collections.nCopies(257, "1"))));
    }

    @Test void timeoutReleasesTheSingleQuerySlot() throws Exception {
        prepared();
        try (var conn = admin(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE N AS SELECT X AS ID FROM SYSTEM_RANGE(1, 100)");
            DatabaseQueryAccess.provision(conn);
        }
        long started = System.nanoTime();
        var failure = assertThrows(SQLException.class, () -> DatabaseQueryUtils.executeQuery(url(),
            "SELECT SUM(a.ID*b.ID*c.ID*d.ID*e.ID) FROM N a CROSS JOIN N b CROSS JOIN N c CROSS JOIN N d CROSS JOIN N e", 100, 1));
        assertEquals(57014, failure.getErrorCode());
        assertTrue(System.nanoTime()-started < TimeUnit.SECONDS.toNanos(20));
        assertEquals(List.of(List.of(2L)), query("SELECT COUNT(*) FROM RESULTS").get("rows"));
    }

    @Test void cleanupPreservesDatabaseAndCrossRunQueries() throws Exception {
        prepared();
        Path run = root.resolve("output/first"), output = run.resolve("database/out");
        Files.createDirectories(output.getParent());
        Files.move(root.resolve("db.mv.db"), run.resolve("database/out.mv.db"));
        Files.writeString(run.resolve("options.txt"), "old parameters");
        String owner = UUID.randomUUID().toString();
        try {
            microsim.data.StorageProtection.protectDatabase(owner, output, "Shared output");
            var storage = new SessionStorage(root, 100000000, 100, 50);
            storage.retire(run);
            var preview = storage.preview();
            storage.delete((String)preview.get("token"), List.of("output/first"));
            assertFalse(Files.exists(run.resolve("options.txt")));
            assertEquals(List.of(List.of(2.0)), DatabaseQueryUtils.executeOutputQuery(output,
                "SELECT b.AMOUNT-a.AMOUNT FROM RESULTS a JOIN RESULTS b ON a.ID=b.ID WHERE a.RUN_ID=1 AND b.RUN_ID=2", 5000, 60).get("rows"));
        } finally { microsim.data.StorageProtection.release(owner); }
    }

    @Test void querySlotRejectsConcurrentWorkAndIsReleasedAfterCancellation() throws Exception {
        prepared();
        try (var conn = admin(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE N AS SELECT X AS ID FROM SYSTEM_RANGE(1,100)");
            DatabaseQueryAccess.provision(conn);
        }
        String jdbc = url();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var slow = pool.submit(() -> {
                try { DatabaseQueryUtils.executeQuery(jdbc,
                    "SELECT SUM(a.ID*b.ID*c.ID*d.ID*e.ID) FROM N a CROSS JOIN N b CROSS JOIN N c CROSS JOIN N d CROSS JOIN N e", 1, 2); }
                catch (SQLException expected) { return expected.getErrorCode(); }
                return 0;
            });
            // Wait until the background call holds the real executor slot; do not race
            // it with the foreground probe during parser/JVM warm-up.
            var field = DatabaseQueryUtils.class.getDeclaredField("QUERY_SLOT"); field.setAccessible(true);
            var slot = (Semaphore)field.get(null);
            long acquiredBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (slot.availablePermits() != 0 && !slow.isDone() && System.nanoTime() < acquiredBy) Thread.sleep(5);
            boolean busy = false;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!busy && System.nanoTime() < until) {
                try { query("SELECT 1"); }
                catch (SQLException rejected) { busy = "JQ003".equals(rejected.getSQLState()); }
                if (!busy) Thread.sleep(20);
            }
            assertTrue(busy);
            assertEquals(57014, slow.get(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of(List.of(1)), query("SELECT 1").get("rows"));
    }

    @Test void provisioningReceiptDescribesTheFinalPreparedFile() throws Exception {
        try (var conn = admin(); var stmt = conn.createStatement()) { stmt.execute("CREATE TABLE T(ID INT)"); }
        Path receipt = root.resolve("query-access.json");
        byte[] source = Files.readAllBytes(root.resolve("db.mv.db"));
        DatabaseQueryAccess.main(new String[]{base().toString(), receipt.toString()});
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(receipt.toFile());
        var hash = java.security.MessageDigest.getInstance("SHA-256");
        assertEquals(java.util.HexFormat.of().formatHex(hash.digest(source)), json.get("source_database_sha256").asText());
        assertEquals(java.util.HexFormat.of().formatHex(hash.digest(Files.readAllBytes(root.resolve("db.mv.db")))), json.get("database_sha256").asText());
        assertEquals(List.of(List.of(0L)), query("SELECT COUNT(*) FROM T").get("rows"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseQueryAccess.main(new String[]{base().toString(), receipt.toString()}));
    }

    @Test void decimalAggregatesWithWideDeclaredPrecisionRemainUsable() throws Exception {
        prepared();
        try (var conn = admin(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE MONEY(AMOUNT DECIMAL(20,2))");
            stmt.execute("INSERT INTO MONEY VALUES(12.34),(56.78)");
            DatabaseQueryAccess.provision(conn);
        }
        var result = (List<?>)query("SELECT SUM(AMOUNT) FROM MONEY").get("rows");
        assertEquals(new java.math.BigDecimal("69.12"), ((List<?>)result.get(0)).get(0));
    }
}
