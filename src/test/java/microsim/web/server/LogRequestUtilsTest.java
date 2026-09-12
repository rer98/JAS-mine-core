package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for LogRequestUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by LogRequestUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class LogRequestUtilsTest {
    @Test
    public void parseIntegerReturnsNullForMissingOrInvalidValues() {
        assertNull(LogRequestUtils.parseInteger(null));
        assertNull(LogRequestUtils.parseInteger(""));
        assertNull(LogRequestUtils.parseInteger("not-an-int"));
        assertEquals(Integer.valueOf(42), LogRequestUtils.parseInteger(" 42 "));
    }

    @Test
    public void boundedIntDefaultsAndClampsValues() {
        assertEquals(50, LogRequestUtils.boundedInt(null, 50, 1, 100));
        assertEquals(1, LogRequestUtils.boundedInt(-5, 50, 1, 100));
        assertEquals(100, LogRequestUtils.boundedInt(500, 50, 1, 100));
        assertEquals(42, LogRequestUtils.boundedInt(42, 50, 1, 100));
    }

    @Test
    public void tailMaxLinesUsesEndpointBounds() {
        assertEquals(200, LogRequestUtils.tailMaxLines(null));
        assertEquals(1, LogRequestUtils.tailMaxLines(-10));
        assertEquals(500, LogRequestUtils.tailMaxLines(999));
    }

    @Test
    public void searchRequestParsesFlagsAndAppliesBounds() {
        LogRequestUtils.SearchRequest req = LogRequestUtils.searchRequest("error", "true", "false", 999, -5);
        assertEquals("error", req.query());
        assertTrue(req.regex());
        assertFalse(req.caseSensitive());
        assertEquals(100, req.maxMatches());
        assertEquals(0, req.context());
    }

    @Test
    public void regexLengthLimitOnlyAppliesToRegexQueries() {
        String longQuery = "x".repeat(SimulationLogBuffer.DEFAULT_MAX_REGEX_LENGTH + 1);
        assertTrue(LogRequestUtils.regexTooLong(LogRequestUtils.searchRequest(longQuery, "true", null, null, null)));
        assertFalse(LogRequestUtils.regexTooLong(LogRequestUtils.searchRequest(longQuery, "false", null, null, null)));
        assertTrue(LogRequestUtils.regexTooLongMessage().contains(String.valueOf(SimulationLogBuffer.DEFAULT_MAX_REGEX_LENGTH)));
    }
}
