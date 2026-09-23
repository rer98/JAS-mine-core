package microsim.web.server;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;


/* (C) Copyright 2026, by Ross Richardson
 *
 * High-level CSV/TSV data-reading helpers for JAS-mine Web exports.
 * Provides column discovery, row windows, summaries, random samples, and filtered row searches
 * for browser export tools and AI diagnostics.
 *
 * @author ross richardson
 *
 */

/** Streaming CSV/TSV readers for export diagnostics endpoints. */
public final class TabularDataUtils {
    private TabularDataUtils() {}

    public static final class MissingColumnException extends Exception {
        private final List<String> columns;

        public MissingColumnException(String column, List<String> columns) {
            super("Column not found: " + column);
            this.columns = columns == null ? new ArrayList<>() : columns;
        }

        public List<String> getColumns() { return columns; }
    }

    private static final long MAX_RESULT_CHARS = 2L * 1024 * 1024;

    private static long textSize(List<String> row) {
        return row == null ? 0 : row.stream().mapToLong(v -> v.length() + 8L).sum();
    }

    private static long keep(long size, long addition) throws DiagnosticLimitException {
        long result = size + addition;
        if (result > MAX_RESULT_CHARS)
            throw new DiagnosticLimitException("Diagnostic result is too large; request fewer rows or download the file.");
        return result;
    }

    public static Map<String, Object> columns(File file, char delim, int previewRows) throws Exception {
        long retained = 0;
        List<String> columns;
        List<List<String>> preview = new ArrayList<>();
        try (CsvRecordReader reader = DiagnosticReader.open(file.toPath(), delim)) {
            columns = reader.readRecord();
            for (int i = 0; i < previewRows; i++) {
                List<String> rec = reader.readRecord();
                if (rec == null) break;
                retained = keep(retained, textSize(rec));
                preview.add(rec);
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("file", file.getName());
        res.put("columns", columns == null ? new ArrayList<>() : columns);
        res.put("previewRows", preview);
        res.put("delimiter", String.valueOf(delim));
        return res;
    }

    public static Map<String, Object> columnSummary(File file, char delim, String column, int topN, int maxDistinctValues) throws Exception {
        long retained = 0;
        List<String> columns;
        long total = 0, missing = 0, numeric = 0;
        Double min = null, max = null, sum = 0.0;
        Map<String, Long> counts = new HashMap<>();
        long distinctOverflow = 0;
        boolean distinctLimited = false;

        try (CsvRecordReader reader = DiagnosticReader.open(file.toPath(), delim)) {
            columns = reader.readRecord();
            int idx = columns == null ? -1 : columns.indexOf(column);
            if (idx < 0) throw new MissingColumnException(column, columns);

            List<String> rec;
            while ((rec = reader.readRecord()) != null) {
                total++;
                String v = idx < rec.size() ? rec.get(idx) : "";
                if (v == null || v.isEmpty() || "null".equalsIgnoreCase(v)) { missing++; continue; }
                if (!counts.containsKey(v) && counts.size() < maxDistinctValues) retained = keep(retained, v.length() + 8L);
                if (!TabularFilterUtils.incrementCappedCount(counts, v, maxDistinctValues)) {
                    distinctLimited = true;
                    distinctOverflow++;
                }
                try {
                    double d = Double.parseDouble(v);
                    numeric++;
                    sum += d;
                    min = min == null ? d : Math.min(min, d);
                    max = max == null ? d : Math.max(max, d);
                } catch (NumberFormatException ignored) {}
            }
        }

        List<Map<String, Object>> topValues = new ArrayList<>();
        counts.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(topN)
            .forEach(e -> topValues.add(Map.of("value", e.getKey(), "count", e.getValue())));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("file", file.getName());
        res.put("column", column);
        res.put("totalRows", total);
        res.put("missing", missing);
        res.put("distinct", counts.size());
        res.put("distinctLimited", distinctLimited);
        res.put("distinctOverflowRows", distinctOverflow);
        res.put("topValues", topValues);
        res.put("numericCount", numeric);
        if (numeric > 0) {
            res.put("min", min);
            res.put("max", max);
            res.put("mean", sum / numeric);
        }
        return res;
    }

    public static Map<String, Object> sampleRows(File file, char delim, int limit, long seed) throws Exception {
        long retained = 0;
        List<String> columns;
        List<List<String>> sample = new ArrayList<>();
        Random rng = new Random(seed);
        long seen = 0;

        try (CsvRecordReader reader = DiagnosticReader.open(file.toPath(), delim)) {
            columns = reader.readRecord();
            List<String> rec;
            while ((rec = reader.readRecord()) != null) {
                seen++;
                if (sample.size() < limit) { retained = keep(retained, textSize(rec)); sample.add(rec); }
                else {
                    long j = (long) (rng.nextDouble() * seen);
                    if (j < limit) {
                        retained = keep(retained, textSize(rec) - textSize(sample.get((int) j)));
                        sample.set((int) j, rec);
                    }
                }
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("file", file.getName());
        res.put("columns", columns == null ? new ArrayList<>() : columns);
        res.put("rows", sample);
        res.put("sampledRows", sample.size());
        res.put("totalRows", seen);
        res.put("seed", seed);
        res.put("delimiter", String.valueOf(delim));
        return res;
    }

    public static Map<String, Object> findRows(File file, char delim, boolean header, List<Map<String, Object>> filters, int limit) throws Exception {
        long retained = 0;
        List<String> columns;
        if (filters.size() > 20) throw new DiagnosticLimitException("At most 20 diagnostic filters are allowed.");
        for (var filter : filters) {
            if (String.valueOf(filter.getOrDefault("value", "")).length() > 4096
                    || String.valueOf(filter.getOrDefault("column", "")).length() > 256)
                throw new DiagnosticLimitException("Diagnostic filter value or column is too long.");
        }
        List<List<String>> rows = new ArrayList<>();
        long scanned = 0;
        boolean hasMore = false;

        try (CsvRecordReader reader = DiagnosticReader.open(file.toPath(), delim)) {
            columns = header ? reader.readRecord() : null;
            List<String> rec;
            while ((rec = reader.readRecord()) != null) {
                scanned++;
                boolean match = true;
                for (Map<String, Object> f : filters) {
                    String col = String.valueOf(f.get("column"));
                    String op = String.valueOf(f.getOrDefault("op", "="));
                    String val = String.valueOf(f.getOrDefault("value", ""));
                    int idx = -1;
                    if (columns != null) idx = columns.indexOf(col);
                    else {
                        try { idx = Integer.parseInt(col); } catch (Exception ignored) {}
                    }
                    if (idx < 0 || idx >= rec.size() || !TabularFilterUtils.csvFilterMatches(rec.get(idx), op, val)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    if (rows.size() < limit) { retained = keep(retained, textSize(rec)); rows.add(rec); }
                    else { hasMore = true; break; }
                }
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("file", file.getName());
        res.put("columns", columns == null ? new ArrayList<>() : columns);
        res.put("rows", rows);
        res.put("returned", rows.size());
        res.put("limit", limit);
        res.put("hasMore", hasMore);
        res.put("scannedRows", scanned);
        res.put("delimiter", String.valueOf(delim));
        return res;
    }

    public static Map<String, Object> rows(File file, char delim, long offset, int limit, boolean header, boolean count) throws Exception {
        long retained = 0;
        List<String> columns = null;
        List<List<String>> rows = new ArrayList<>();
        long seen = 0;
        boolean hasMore = false;
        boolean reachedEof = false;

        try (CsvRecordReader csv = DiagnosticReader.open(file.toPath(), delim)) {
            if (header) {
                List<String> head = TabularExportUtils.nextNonBlankRecord(csv);
                if (head != null) columns = head;
            }
            List<String> rec;
            while ((rec = TabularExportUtils.nextNonBlankRecord(csv)) != null) {
                if (seen >= offset && rows.size() < limit) {
                    retained = keep(retained, textSize(rec));
                    rows.add(rec);
                } else if (seen >= offset && rows.size() >= limit) {
                    hasMore = true;
                    if (!count) break;
                }
                seen++;
            }
            if (rec == null) reachedEof = true;
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("file", file.getName());
        res.put("columns", columns);
        res.put("rows", rows);
        res.put("offset", offset);
        res.put("returned", rows.size());
        res.put("limit", limit);
        res.put("headerIncluded", header && columns != null);
        res.put("hasMore", hasMore);
        res.put("delimiter", String.valueOf(delim));
        res.put("totalDataRows", (count && reachedEof) ? seen : null);
        return res;
    }
}
