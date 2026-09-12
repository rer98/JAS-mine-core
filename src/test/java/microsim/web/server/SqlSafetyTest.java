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
        assertFalse(SqlSafety.hasMultipleStatements("select ';' as semi"));
        assertFalse(SqlSafety.hasMultipleStatements("select 1 -- ; in comment\n"));
        assertTrue(SqlSafety.hasMultipleStatements("select 1; drop table TEST"));
    }
}
