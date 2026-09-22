package microsim.web.server;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_LINE_BYTES = 64 * 1024;
    private static final String TRUNCATED = " [line truncated at console limit]";
    private int retainedBytes;

    private static final String LOG_RUN_MARKER = "--- Building New Simulation";
    private static final String LOG_OUTPUT_RUN_PREFIX = "--- Output run: ";

    private final int maxLines;
    private final ArrayDeque<String> logBuffer = new ArrayDeque<>();
    private final Object bufferLock = new Object();
    /** Monotonic counter for the index of the oldest line still in {@link #logBuffer}.
     *  Incremented whenever a line is dropped due to the buffer reaching {@link #maxLines}.
     *  Combined with the buffer's current length, this gives a stable global line index that
     *  clients can use as a {@code since} cursor across buffer rollovers. */
    private long firstLogIndex = 0L; // Guarded by bufferLock, together with logBuffer.

    public SimulationLogBuffer(int maxLines) {
        if (maxLines < 1) throw new IllegalArgumentException("maxLines must be positive");
        this.maxLines = maxLines;
    }

    public void add(String message) {
        message = message == null ? "" : message;
        // Bound conversion itself when a caller supplies a very large String.
        if (message.length() > MAX_LINE_BYTES) message = message.substring(0, MAX_LINE_BYTES) + TRUNCATED;
        byte[] encoded = message.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_LINE_BYTES)
            message = new String(encoded, 0, MAX_LINE_BYTES - 128, StandardCharsets.UTF_8) + TRUNCATED;
        synchronized (bufferLock) {
            logBuffer.addLast(message);
            retainedBytes += message.getBytes(StandardCharsets.UTF_8).length + 1;
            while (logBuffer.size() > maxLines || retainedBytes > MAX_BYTES) {
                retainedBytes -= logBuffer.removeFirst().getBytes(StandardCharsets.UTF_8).length + 1;
                firstLogIndex++;
            }
        }
    }

    private record Snapshot(List<String> lines, long firstIndex) {}

    private Snapshot snapshot() {
        synchronized (bufferLock) {
            return new Snapshot(new ArrayList<>(logBuffer), firstLogIndex);
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
        private boolean truncated;

        LineCapturingOutputStream(PrintStream passthrough) {
            this.passthrough = passthrough;
        }

        @Override public synchronized void write(int b) {
            if (b == '\n') {
                String line = buf.toString(StandardCharsets.UTF_8) + (truncated ? TRUNCATED : "");
                add(line);
                passthrough.println(line);
                buf.reset();
                truncated = false;
            } else {
                if (buf.size() < MAX_LINE_BYTES - 128) buf.write(b);
                else truncated = true;
            }
        }
    }

    public Map<String, Object> poll(long since) {
        Snapshot snapshot = snapshot();
        List<String> logs = snapshot.lines();
        long first = snapshot.firstIndex();
        int start = since <= first ? 0 : (int) Math.min(since - first, logs.size());
        List<String> newLogs = logs.subList(start, logs.size());

        return Map.of(
            "logs", redactLogLines(newLogs),
            "firstIndex", first,
            "nextIndex", first + logs.size(),
            "truncatedBefore", since < first,
            "maxBytes", MAX_BYTES,
            "maxLineBytes", MAX_LINE_BYTES
        );
    }

    private static class LogScope {
        final long firstIndex;
        final List<String> all;
        final List<String> current;
        final int start;
        final String outputRun;

        LogScope(Snapshot snapshot, int start, String outputRun) {
            this.firstIndex = snapshot.firstIndex();
            this.all = snapshot.lines();
            this.start = start;
            this.current = this.all.subList(start, this.all.size());
            this.outputRun = outputRun;
        }
    }

    private LogScope currentRunLogScope() {
        Snapshot snapshot = snapshot();
        List<String> logs = snapshot.lines();
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
        return new LogScope(snapshot, start, outputRun);
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
        long first = scope.firstIndex;
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
        long first = scope.firstIndex + scope.start;
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
        out.put("nextIndex", scope.firstIndex + scope.all.size());
        out.put("outputRun", scope.outputRun);
        out.put("truncatedMatches", matches.size() >= maxMatches);
        out.put("note", "Current-run console log search, scoped from the latest Building New Simulation marker when present. Secrets/tokens/password-like values are redacted heuristically.");
        return out;
    }
}
