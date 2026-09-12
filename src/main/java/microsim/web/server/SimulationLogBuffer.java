package microsim.web.server;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


/* (C) Copyright 2026, by Ross Richardson
 *
 * In-memory simulation log buffer for JAS-mine Web.
 * Stores bounded console output, tracks current-run scope, and supports tail/search responses
 * with stable cursors, context lines, truncation flags, and output-run annotations.
 *
 * @author ross richardson
 *
 */

/**
 * Thread-safe append-only-ish simulation log buffer with rollover indexing,
 * current-run scoping, redaction, tail, search, and stdout/stderr capture.
 */
public class SimulationLogBuffer {
    public static final int DEFAULT_MAX_LINES = 100_000;
    public static final int DEFAULT_MAX_REGEX_LENGTH = 500;

    private static final String LOG_RUN_MARKER = "--- Building New Simulation";
    private static final String LOG_OUTPUT_RUN_PREFIX = "--- Output run: ";

    private final int maxLines;
    private final ConcurrentLinkedQueue<String> logBuffer = new ConcurrentLinkedQueue<>();
    /** Monotonic counter for the index of the oldest line still in {@link #logBuffer}.
     *  Incremented whenever a line is dropped due to the buffer reaching {@link #maxLines}.
     *  Combined with the buffer's current length, this gives a stable global line index that
     *  clients can use as a {@code since} cursor across buffer rollovers. */
    private final AtomicLong firstLogIndex = new AtomicLong(0L);

    public SimulationLogBuffer(int maxLines) {
        if (maxLines < 1) throw new IllegalArgumentException("maxLines must be positive");
        this.maxLines = maxLines;
    }

    public void add(String message) {
        logBuffer.offer(message);
        if (logBuffer.size() > maxLines) {
            if (logBuffer.poll() != null) {
                firstLogIndex.incrementAndGet();
            }
        }
    }

    /**
     * Captures bytes written to an OutputStream as UTF-8 text, splitting on '\n' and
     * forwarding each complete line both to this log buffer and to a passthrough stream.
     * Buffers raw bytes (not chars) so that multi-byte UTF-8 sequences such as £, é, ü
     * are not corrupted during capture.
     */
    public OutputStream capturingOutputStream(PrintStream passthrough) {
        return new LineCapturingOutputStream(passthrough);
    }

    private class LineCapturingOutputStream extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final PrintStream passthrough;

        LineCapturingOutputStream(PrintStream passthrough) {
            this.passthrough = passthrough;
        }

        @Override public synchronized void write(int b) {
            if (b == '\n') {
                String line = buf.toString(StandardCharsets.UTF_8);
                add(line);
                passthrough.println(line);
                buf.reset();
            } else {
                buf.write(b);
            }
        }
    }

    public Map<String, Object> poll(long since) {
        // Read array first, then firstLogIndex: under concurrent rotation this risks at
        // worst a small number of duplicated lines being sent (never silent gaps).
        String[] logs = logBuffer.toArray(new String[0]);
        long first = firstLogIndex.get();

        int start = (int) Math.max(0L, since - first);
        if (start > logs.length) start = logs.length;

        List<String> newLogs = new ArrayList<>();
        for (int i = start; i < logs.length; i++) {
            newLogs.add(logs[i]);
        }

        return Map.of(
            "logs", newLogs,
            "firstIndex", first,
            "nextIndex", first + logs.length
        );
    }

    private static class LogScope {
        final List<String> all;
        final List<String> current;
        final int start;
        final String outputRun;

        LogScope(List<String> all, int start, String outputRun) {
            this.all = all;
            this.start = start;
            this.current = all.subList(start, all.size());
            this.outputRun = outputRun;
        }
    }

    private List<String> currentLogSnapshot() {
        return new ArrayList<>(Arrays.asList(logBuffer.toArray(new String[0])));
    }

    private LogScope currentRunLogScope() {
        List<String> logs = currentLogSnapshot();
        int start = 0;
        String outputRun = null;
        for (int i = logs.size() - 1; i >= 0; i--) {
            String line = logs.get(i);
            if (line == null) continue;
            if (outputRun == null) {
                int pos = line.indexOf(LOG_OUTPUT_RUN_PREFIX);
                if (pos >= 0) {
                    String candidate = line.substring(pos + LOG_OUTPUT_RUN_PREFIX.length()).replace("---", "").trim();
                    if (!candidate.isEmpty()) outputRun = candidate;
                }
            }
            if (line.contains(LOG_RUN_MARKER)) {
                start = i;
                break;
            }
        }
        return new LogScope(logs, start, outputRun);
    }

    private static List<String> redactLogLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String s = line == null ? "" : line;
            s = s.replaceAll("(?i)(api[_-]?key|token|secret|password|passwd|authorization|bearer)([\\s:=]+)([^\\s,;]+)", "$1$2[REDACTED]");
            s = s.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1[REDACTED]");
            out.add(s);
        }
        return out;
    }

    public Map<String, Object> tail(int maxLines) {
        LogScope scope = currentRunLogScope();
        long first = firstLogIndex.get();
        int start = Math.max(0, scope.current.size() - maxLines);
        List<String> lines = redactLogLines(scope.current.subList(start, scope.current.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", lines);
        out.put("returned", lines.size());
        out.put("maxLines", maxLines);
        out.put("firstIndex", first);
        out.put("scopeStartIndex", first + scope.start);
        out.put("startIndex", first + scope.start + start);
        out.put("nextIndex", first + scope.all.size());
        out.put("truncatedBefore", start > 0);
        out.put("outputRun", scope.outputRun);
        out.put("note", "Current-run console log tail, scoped from the latest Building New Simulation marker when present. Secrets/tokens/password-like values are redacted heuristically.");
        return out;
    }

    public Map<String, Object> search(String query, boolean useRegex, boolean useCase, int maxMatches, int context)
            throws PatternSyntaxException {
        LogScope scope = currentRunLogScope();
        List<String> logs = scope.current;
        long first = firstLogIndex.get() + scope.start;
        List<Map<String, Object>> matches = new ArrayList<>();
        Pattern pat = null;
        String q = useCase ? query : query.toLowerCase(Locale.ROOT);
        if (useRegex) {
            pat = Pattern.compile(query, useCase ? 0 : Pattern.CASE_INSENSITIVE);
        }
        for (int i = 0; i < logs.size() && matches.size() < maxMatches; i++) {
            String line = logs.get(i) == null ? "" : logs.get(i);
            boolean hit = useRegex ? pat.matcher(line).find() : (useCase ? line : line.toLowerCase(Locale.ROOT)).contains(q);
            if (!hit) continue;
            int a = Math.max(0, i - context);
            int b = Math.min(logs.size(), i + context + 1);
            matches.add(Map.of(
                "index", first + i,
                "line", redactLogLines(List.of(line)).get(0),
                "contextStartIndex", first + a,
                "contextLines", redactLogLines(logs.subList(a, b))
            ));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("regex", useRegex);
        out.put("caseSensitive", useCase);
        out.put("matches", matches);
        out.put("returned", matches.size());
        out.put("maxMatches", maxMatches);
        out.put("searchedLines", logs.size());
        out.put("firstIndex", first);
        out.put("scopeStartIndex", first);
        out.put("nextIndex", firstLogIndex.get() + scope.all.size());
        out.put("outputRun", scope.outputRun);
        out.put("truncatedMatches", matches.size() >= maxMatches);
        out.put("note", "Current-run console log search, scoped from the latest Building New Simulation marker when present. Secrets/tokens/password-like values are redacted heuristically.");
        return out;
    }
}
