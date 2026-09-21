/* (C) Copyright 2026, by Ross Richardson
 * Bounded candidate uploads, separate from active model inputs.
 * @author ross richardson
 */
package microsim.web.server;

import java.io.*;
import java.nio.file.*;

public final class StartupUploads {
    private StartupUploads() {}
    public static void store(Path root, String relative, InputStream body, long fileLimit,
            long totalLimit, long freeReserve) throws IOException {
        Files.createDirectories(root);
        if (relative == null || relative.isBlank()) throw new IllegalArgumentException("Missing candidate file path");
        var resolved = InputFileUtils.resolveInputPath(root.toFile(), relative);
        if (resolved == null || relative == null || relative.isBlank())
            throw new IllegalArgumentException("Invalid candidate file path");
        Path target = resolved.toPath();
        Files.createDirectories(target.getParent());
        long used;
        try (var files = Files.walk(root)) {
            used = files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
        long available = Math.min(fileLimit, Math.min(totalLimit - used + (Files.isRegularFile(target) ? Files.size(target) : 0),
                Files.getFileStore(root).getUsableSpace() - freeReserve));
        var session = SessionStorage.fromEnvironment().status();
        if (Boolean.TRUE.equals(session.get("enabled")))
            available = Math.min(available, ((Number)session.get("remainingBytes")).longValue() - freeReserve);
        if (available <= 0) throw new IOException("Insufficient space for startup uploads; remove candidates or free session storage.");
        Path temp = Files.createTempFile(root, ".upload-", ".part");
        try {
            try (var out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                long count = 0;
                for (int n; (n = body.read(buffer)) != -1;) {
                    count += n;
                    if (count > available) throw new IOException("Upload exceeds file, candidate-storage or free-space limit");
                    out.write(buffer, 0, n);
                }
                if (count == 0) throw new IOException("Empty uploads are not accepted");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
