package microsim.web.server;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

/* (C) Copyright 2026, by Ross Richardson
 * Bounded CSV diagnostic scans; model import readers retain their own limits.
 * @author ross richardson
 */
final class DiagnosticReader extends FilterReader {
    private static final Semaphore SLOTS = new Semaphore(2);
    private final long deadline = System.nanoTime() + 10_000_000_000L;
    private long remaining = 128L * 1024 * 1024;
    private boolean closed;

    private DiagnosticReader(Reader reader) { super(reader); }

    static CsvRecordReader open(Path path, char delimiter) throws IOException {
        if (!SLOTS.tryAcquire()) throw new DiagnosticLimitException("Diagnostics are busy. Please retry shortly.");
        try {
            return new CsvRecordReader(new DiagnosticReader(Files.newBufferedReader(path, StandardCharsets.UTF_8)),
                                       delimiter, 256 * 1024, 64 * 1024, 2048);
        } catch (IOException | RuntimeException e) {
            SLOTS.release();
            throw e;
        }
    }

    void checkDeadline() throws DiagnosticLimitException {
        if (System.nanoTime() > deadline)
            throw new DiagnosticLimitException("Diagnostic scan timed out; request fewer rows or download the file. No complete summary was produced.");
    }

    @Override public int read(char[] buffer, int offset, int length) throws IOException {
        checkDeadline();
        if (remaining <= 0)
            throw new DiagnosticLimitException("Diagnostic scan limit reached; request fewer rows or download the file. No complete summary was produced.");
        int n = super.read(buffer, offset, (int) Math.min(length, remaining));
        if (n > 0) remaining -= n;
        return n;
    }

    @Override public int read() throws IOException {
        char[] one = new char[1];
        return read(one, 0, 1) < 0 ? -1 : one[0];
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { super.close(); } finally { SLOTS.release(); }
    }
}
