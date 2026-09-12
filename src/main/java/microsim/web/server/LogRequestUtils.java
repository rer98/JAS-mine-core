package microsim.web.server;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Request parsing helpers for simulation log tail and search endpoints.
 * Parses bounded integer and boolean query parameters, shapes log-search requests,
 * and enforces regex length limits before querying the log buffer.
 *
 * @author ross richardson
 *
 */

/** Request parsing helpers for simulation log endpoints. */
public final class LogRequestUtils {
    private LogRequestUtils() {}

    public record SearchRequest(String query, boolean regex, boolean caseSensitive, int maxMatches, int context) {}

    public static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int boundedInt(Integer value, int def, int min, int max) {
        int v = value == null ? def : value;
        return Math.max(min, Math.min(max, v));
    }

    public static int tailMaxLines(Integer value) {
        return boundedInt(value, 200, 1, 500);
    }

    public static SearchRequest searchRequest(String query, String regex, String caseSensitive, Integer maxMatches, Integer context) {
        return new SearchRequest(
            query,
            flag(regex),
            flag(caseSensitive),
            boundedInt(maxMatches, 50, 1, 100),
            boundedInt(context, 10, 0, 50)
        );
    }

    public static boolean flag(String value) {
        return Boolean.parseBoolean(value == null ? "false" : value);
    }

    public static String regexTooLongMessage() {
        return "Regex query is too long; maximum length is " + SimulationLogBuffer.DEFAULT_MAX_REGEX_LENGTH;
    }

    public static boolean regexTooLong(SearchRequest req) {
        return req.regex() && req.query() != null && req.query().length() > SimulationLogBuffer.DEFAULT_MAX_REGEX_LENGTH;
    }
}
