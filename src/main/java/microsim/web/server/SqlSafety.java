package microsim.web.server;

import java.util.Locale;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Read-only SQL safety checks for the JAS-mine Web DB Explorer.
 * Identifies the first SQL keyword, permits SELECT/WITH queries, rejects multiple statements,
 * and blocks write or schema-changing SQL before JDBC execution.
 *
 * @author ross richardson
 *
 */

/** SQL validation helpers for read-only DB Explorer queries. */
public final class SqlSafety {
    private SqlSafety() {}

    /**
     * Returns the first SQL keyword after leading whitespace, comments, and opening
     * parentheses. This keeps validation friendly for pasted queries with leading
     * line comments, block comments, or parentheses without trying to implement
     * a full SQL parser.
     */
    public static String firstSqlKeyword(String sql) {
        if (sql == null) return "";
        int i = 0, n = sql.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(sql.charAt(i))) i++;
            if (i + 1 < n && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (i + 1 < n && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return "";
                i = end + 2;
                continue;
            }
            if (i < n && sql.charAt(i) == '(') {
                i++;
                continue;
            }
            break;
        }
        int start = i;
        while (i < n && Character.isLetter(sql.charAt(i))) i++;
        return sql.substring(start, i).toLowerCase(Locale.ROOT);
    }

    /** Returns true for read-only result-set queries supported by the DB explorer. */
    public static boolean isAllowedReadOnlyQuery(String sql) {
        String keyword = firstSqlKeyword(sql);
        return "select".equals(keyword) || "with".equals(keyword);
    }

    /**
     * Returns true if {@code sql} contains a statement separator ({@code ;}) outside of
     * quoted strings or SQL comments, ignoring a single trailing {@code ;}. Used as a
     * second layer of defence (alongside H2's {@code ACCESS_MODE_DATA=r}) to reject
     * chained statements such as {@code SELECT 1; DROP TABLE foo}.
     */
    public static boolean hasMultipleStatements(String sql) {
        String s = sql.trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {            // quoted string / identifier
                char q = c;
                i++;
                while (i < n) {
                    if (s.charAt(i) == q) {
                        if (i + 1 < n && s.charAt(i + 1) == q) { i += 2; continue; }  // doubled = escape
                        i++; break;
                    }
                    i++;
                }
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {     // line comment
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {     // block comment
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
            } else if (c == ';') {
                return true;
            } else {
                i++;
            }
        }
        return false;
    }
}
