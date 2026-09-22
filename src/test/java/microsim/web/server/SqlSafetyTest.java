package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for SqlSafety.
 * Verifies the focused JAS-mine Web helper behaviour implemented by SqlSafety
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class SqlSafetyTest {
    @Test
    public void firstSqlKeywordSkipsWhitespaceCommentsAndParentheses() {
        assertEquals("select", SqlSafety.firstSqlKeyword("  -- hello\n(select 1)"));
        assertEquals("with", SqlSafety.firstSqlKeyword("/* comment */ WITH x AS (SELECT 1) SELECT * FROM x"));
        assertEquals("", SqlSafety.firstSqlKeyword("/* unterminated"));
    }

    @Test
    public void readOnlyQueryAllowsOnlySelectAndWith() {
        assertTrue(SqlSafety.isAllowedReadOnlyQuery("select * from TEST"));
        assertTrue(SqlSafety.isAllowedReadOnlyQuery("with x as (select 1) select * from x"));
        assertFalse(SqlSafety.isAllowedReadOnlyQuery("update TEST set X = 1"));
        assertFalse(SqlSafety.isAllowedReadOnlyQuery("drop table TEST"));
    }

    @Test
    public void multipleStatementDetectionIgnoresTrailingSemicolonAndQuotedSemicolons() {
        assertFalse(SqlSafety.hasMultipleStatements("select 1;"));
        assertFalse(SqlSafety.hasMultipleStatements("select ';' as punctuation"));
        assertFalse(SqlSafety.hasMultipleStatements("select 1 -- ; in comment\n"));
        assertTrue(SqlSafety.hasMultipleStatements("select 1; drop table TEST"));
    }

    @Test
    void supportedAnalyticalQueries() {
        String[] queries = {
            "SELECT COUNT(*), AVG(amount), STDDEV_SAMP(amount) FROM results GROUP BY run_id HAVING COUNT(*) > 0",
            "SELECT a.run_id, SUM(a.amount)-SUM(b.amount) AS difference FROM results a JOIN results b ON a.id=b.id WHERE a.run_id=1 AND b.run_id=2 GROUP BY a.run_id",
            "WITH old_run AS (SELECT * FROM results WHERE run_id=1), new_run AS (SELECT * FROM results WHERE run_id=2) SELECT COUNT(*) FROM old_run JOIN new_run USING(id)",
            "SELECT CASE WHEN amount IS NULL THEN 0 ELSE amount END FROM results WHERE id IN (SELECT id FROM people)",
            "SELECT ROW_NUMBER() OVER (PARTITION BY gender ORDER BY year) FROM people",
            "SELECT CAST(amount AS DECIMAL(16,2)), ROUND(amount, 2), COALESCE(name, 'n/a') FROM results",
            "SELECT EXTRACT(YEAR FROM created), LOWER(name) FROM results WHERE name LIKE 'A%'",
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",
            "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='PERSON'",
            "SELECT id FROM people UNION ALL SELECT id FROM results",
            "SELECT id FROM people ORDER BY id LIMIT 50 OFFSET 10",
            "SELECT ';' AS punctuation, 'a''b' AS quoted"
        };
        for (String query : queries) assertDoesNotThrow(() -> SqlSafety.check(query), query);
    }

    @Test
    void rejectsSideEffectsAndUnreviewedSyntaxAtEveryDepth() {
        String[] queries = {
            "SELECT E'nonstandard'", "SELECT $$dollar quoting$$",
            "SELECT FILE_READ('/tmp/secret')", "SELECT FILE_WRITE('data', '/tmp/probe')",
            "WITH x AS (SELECT FILE_READ('/tmp/secret')) SELECT * FROM x",
            "SELECT CASE WHEN 1=0 THEN FILE_READ('/tmp/secret') ELSE NULL END",
            "SELECT * FROM CSVREAD('/tmp/secret')", "SELECT * FROM SYSTEM_RANGE(1, 999999999)",
            "SELECT MY_JAVA_ALIAS(1)", "SELECT PUBLIC.FILE_READ('/tmp/secret')",
            "SELECT \"FILE_READ\"('/tmp/secret')", "SELECT NEXT VALUE FOR seq",
            "SELECT * FROM people FOR UPDATE", "SELECT * INTO output_table FROM people",
            "WITH x AS (DELETE FROM people RETURNING *) SELECT * FROM x",
            "WITH RECURSIVE x(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM x) SELECT * FROM x",
            "SELECT CAST('x' AS JAVA_OBJECT)", "SELECT 1; DELETE FROM people",
            "SELECT * FROM people WHERE id=(SELECT FILE_READ('/tmp/secret'))",
            "SELECT * FROM otherdb.PUBLIC.people", "SELECT * FROM people; CALL FILE_READ('/tmp/secret')"
        };
        for (String query : queries) assertThrows(IllegalArgumentException.class, () -> SqlSafety.check(query), query);
    }

    @Test
    void cteNamesCannotHidePhysicalTablesInTheirOwnDefinitions() {
        var query = SqlSafety.check("WITH hidden AS (SELECT * FROM hidden) SELECT * FROM hidden");
        assertTrue(query.tables().contains(new DatabaseQueryAccess.Relation("PUBLIC", "HIDDEN")));
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.checkCatalogue(query,
            new DatabaseQueryAccess.Catalogue(java.util.Set.of(), java.util.Set.of())));
    }

    @Test
    void oversizedSqlAndParserErrorsAreSafe() {
        assertThrows(IllegalArgumentException.class, () -> SqlSafety.check("SELECT '" + "x".repeat(65536) + "'"));
        var error = assertThrows(IllegalArgumentException.class, () -> SqlSafety.check("SELECT super_private_marker !!"));
        assertFalse(error.getMessage().contains("super_private_marker"));
    }
}
