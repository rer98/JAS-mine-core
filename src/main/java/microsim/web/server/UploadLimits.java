/* (C) Copyright 2026, by Ross Richardson
 * Stream upload bytes under file, temporary-space and session allowance bounds.
 * @author ross richardson
 */
package microsim.web.server;

import java.io.*;
import java.nio.file.*;

public final class UploadLimits {
    public static final long FILE_BYTES = 512L * 1024 * 1024;
    public static final long RESERVE_BYTES = 256L * 1024 * 1024;
    public static final int CANDIDATE_FILES = 256;
    private UploadLimits() {}

    public static InputStream bounded(InputStream input) {
        return new FilterInputStream(input) {
            private long count;
            private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
            private void checkTime() throws IOException {
                if (System.nanoTime() > deadline) throw new LimitException(408, "Upload transfer timed out; original file retained.");
            }
            private void add(int n) throws IOException {
                checkTime();
                if (n > 0 && (count += n) > FILE_BYTES) throw new LimitException(413, "Upload exceeds 512 MiB");
            }
            @Override public int read() throws IOException { checkTime(); int b = in.read(); add(b < 0 ? 0 : 1); return b; }
            @Override public int read(byte[] b, int off, int len) throws IOException { checkTime(); int n = in.read(b, off, len); add(n); return n; }
        };
    }

    public static long available(Path directory, long limit) throws IOException {
        long available = Math.min(limit, Files.getFileStore(directory).getUsableSpace() - RESERVE_BYTES);
        var status = SessionStorage.fromEnvironment().status();
        if (Boolean.TRUE.equals(status.get("enabled")))
            available = Math.min(available, ((Number)status.get("remainingBytes")).longValue() - RESERVE_BYTES);
        if (available <= 0) throw new LimitException(507, "Insufficient space for an upload; free session storage and retry.");
        return available;
    }

    public static void copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long count = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
            count += n;
            if (count > limit) throw new LimitException(limit < FILE_BYTES ? 507 : 413,
                    "Upload exceeds the file or available storage limit; original file retained.");
            output.write(buffer, 0, n);
        }
    }

    public static final class LimitException extends IOException {
        public final int status;
        public LimitException(int status, String message) { super(message); this.status = status; }
    }
}
