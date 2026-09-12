package microsim.web.server;

import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Tabular row-filtering helper for export search endpoints.
 * Evaluates text, case-insensitive text, equality, inequality, and numeric comparison filters
 * against CSV/TSV cell values.
 *
 * @author ross richardson
 *
 */

/** Utility helpers for CSV/TSV export filtering and capped value counting. */
public final class TabularFilterUtils {
    private TabularFilterUtils() {}

    public static boolean incrementCappedCount(Map<String, Long> counts, String value, int maxDistinctValues) {
        if (counts.containsKey(value)) {
            counts.merge(value, 1L, Long::sum);
            return true;
        }
        if (counts.size() >= maxDistinctValues) return false;
        counts.put(value, 1L);
        return true;
    }

    public static boolean csvFilterMatches(String actual, String op, String expected) {
        String a = actual == null ? "" : actual;
        String e = expected == null ? "" : expected;
        String cmp = op == null ? "=" : op.trim().toLowerCase(Locale.ROOT);
        switch (cmp) {
            case "=":
            case "==":
                return a.equals(e);
            case "!=":
            case "<>":
                return !a.equals(e);
            case ">":
                return numericCompare(a, e, (x, y) -> x > y);
            case ">=":
                return numericCompare(a, e, (x, y) -> x >= y);
            case "<":
                return numericCompare(a, e, (x, y) -> x < y);
            case "<=":
                return numericCompare(a, e, (x, y) -> x <= y);
            case "contains":
                return a.contains(e);
            case "icontains":
                return a.toLowerCase(Locale.ROOT).contains(e.toLowerCase(Locale.ROOT));
            default:
                return false;
        }
    }

    private static boolean numericCompare(String actual, String expected, BiPredicate<Double, Double> cmp) {
        try {
            return cmp.test(Double.parseDouble(actual.trim()), Double.parseDouble(expected.trim()));
        } catch (Exception e) {
            return false;
        }
    }
}
