package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for TabularFilterUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by TabularFilterUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class TabularFilterUtilsTest {
    @Test
    public void incrementCappedCountCountsExistingValuesButRejectsNewValuesAtCap() {
        Map<String, Long> counts = new HashMap<>();
        assertTrue(TabularFilterUtils.incrementCappedCount(counts, "a", 2));
        assertTrue(TabularFilterUtils.incrementCappedCount(counts, "b", 2));
        assertFalse(TabularFilterUtils.incrementCappedCount(counts, "c", 2));
        assertEquals(2, counts.size());
        assertTrue(TabularFilterUtils.incrementCappedCount(counts, "a", 2));
        assertEquals(Long.valueOf(2), counts.get("a"));
    }

    @Test
    public void csvFilterMatchesSupportsEqualityAndInequalityOperators() {
        assertTrue(TabularFilterUtils.csvFilterMatches("abc", "=", "abc"));
        assertTrue(TabularFilterUtils.csvFilterMatches("abc", "==", "abc"));
        assertTrue(TabularFilterUtils.csvFilterMatches("abc", "!=", "xyz"));
        assertTrue(TabularFilterUtils.csvFilterMatches("abc", "<>", "xyz"));
        assertFalse(TabularFilterUtils.csvFilterMatches("abc", "=", "xyz"));
        assertFalse(TabularFilterUtils.csvFilterMatches("abc", "!=", "abc"));
    }

    @Test
    public void csvFilterMatchesSupportsNumericOperators() {
        assertTrue(TabularFilterUtils.csvFilterMatches("10", ">", "2"));
        assertTrue(TabularFilterUtils.csvFilterMatches("10", ">=", "10"));
        assertTrue(TabularFilterUtils.csvFilterMatches("2", "<", "10"));
        assertTrue(TabularFilterUtils.csvFilterMatches("2", "<=", "2"));
        assertFalse(TabularFilterUtils.csvFilterMatches("abc", ">", "2"));
        assertFalse(TabularFilterUtils.csvFilterMatches("10", ">", "abc"));
    }

    @Test
    public void csvFilterMatchesSupportsTextContainsOperators() {
        assertTrue(TabularFilterUtils.csvFilterMatches("abc", "contains", "b"));
        assertTrue(TabularFilterUtils.csvFilterMatches("Abc", "icontains", "a"));
        assertFalse(TabularFilterUtils.csvFilterMatches("Abc", "contains", "a"));
    }

    @Test
    public void csvFilterMatchesHandlesNullsAndUnknownOperatorsSafely() {
        assertTrue(TabularFilterUtils.csvFilterMatches(null, "=", ""));
        assertTrue(TabularFilterUtils.csvFilterMatches("", "=", null));
        assertTrue(TabularFilterUtils.csvFilterMatches("abc", null, "abc"));
        assertFalse(TabularFilterUtils.csvFilterMatches("abc", "startsWith", "a"));
    }
}
