package microsim.web.server;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Low-level tabular export helpers for CSV/TSV files.
 * Handles CSV escaping, delimiter selection, numeric query parsing, and skipping blank records
 * while sharing the streaming CsvRecordReader implementation.
 *
 * @author ross richardson
 *
 */

/** Utility methods for tabular CSV/TSV export and diagnostics endpoints. */
public final class TabularExportUtils {
    private TabularExportUtils() {}

    /** Escape a CSV field per RFC 4180. */
    public static String escapeCsv(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    /** Parse a long query parameter, returning {@code def} when missing or malformed. */
    public static long parseLongQueryParam(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** Read the next CSV record, skipping wholly-blank lines. Returns null at end of file. */
    public static List<String> nextNonBlankRecord(CsvRecordReader csv) throws IOException {
        List<String> rec;
        while ((rec = csv.readRecord()) != null) {
            if (rec.size() == 1 && rec.get(0).isEmpty()) continue;
            return rec;
        }
        return null;
    }

    /** Choose an explicit delimiter if provided, otherwise infer CSV comma or TSV tab from filename. */
    public static char delimiterForFile(File file, Object delimiter) {
        if (delimiter instanceof String && !((String) delimiter).isEmpty()) return ((String) delimiter).charAt(0);
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".tsv") ? '\t' : ',';
    }
}
