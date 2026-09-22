package microsim.web.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Streaming CSV/TSV record reader for export and diagnostic data.
 * Reads one logical record at a time while handling quoted fields, embedded delimiters,
 * embedded newlines, and escaped quotes without loading entire files into memory.
 *
 * @author ross richardson
 *
 */

/**
 * Streaming, RFC 4180-style CSV/TSV record reader. Quoted fields may contain the
 * delimiter, embedded newlines, and escaped quotes (""), so a record may span
 * multiple physical lines. Only one record is materialised at a time, so the
 * whole file is never held in memory.
 */
public final class CsvRecordReader implements AutoCloseable {
    private final PushbackReader in;
    private final char delim;
    private boolean eof = false;
    private final int maxRecord;
    private final int maxField;
    private final int maxColumns;

    public CsvRecordReader(Reader reader, char delim) {
        this(reader, delim, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public CsvRecordReader(Reader reader, char delim, int maxRecord, int maxField, int maxColumns) {
        this.in = new PushbackReader(new BufferedReader(reader), 1);
        this.delim = delim;
        this.maxRecord = maxRecord;
        this.maxField = maxField;
        this.maxColumns = maxColumns;
    }

    public List<String> readRecord() throws IOException {
        if (eof) return null;
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean anyContent = false;
        int c;
        int size = 0;
        while (true) {
            if (++size > maxRecord || field.length() > maxField || fields.size() >= maxColumns)
                throw new IOException("Tabular record exceeds the record, field or column limit");
            c = in.read();
            if (c == -1) {
                eof = true;
                if (!anyContent && fields.isEmpty() && field.length() == 0) return null;
                fields.add(field.toString());
                return fields;
            }
            char ch = (char) c;
            if (inQuotes) {
                if (ch == '"') {
                    int next = in.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (next != -1) in.unread(next);
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.length() == 0) {
                inQuotes = true;
                anyContent = true;
            } else if (ch == delim) {
                fields.add(field.toString());
                field.setLength(0);
                anyContent = true;
            } else if (ch == '\n') {
                fields.add(field.toString());
                return fields;
            } else if (ch == '\r') {
                int next = in.read();
                if (next != '\n' && next != -1) in.unread(next);
                fields.add(field.toString());
                return fields;
            } else {
                field.append(ch);
                anyContent = true;
            }
        }
    }

    @Override public void close() throws IOException {
        in.close();
    }
}
