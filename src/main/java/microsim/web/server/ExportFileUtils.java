package microsim.web.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Export file and ZIP mechanics for JAS-mine Web output downloads.
 * Lists files recursively, creates run ZIPs, recognises already-compressed files,
 * and classifies expected client-disconnect streaming exceptions.
 *
 * @author ross richardson
 *
 */

/** Utility methods for export listing, compression decisions, and zip creation. */
public final class ExportFileUtils {
    private ExportFileUtils() {}

    public static void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) {
            System.err.println("Warning: Cannot read directory " + folder.getPath());
            throw new IOException("Failed to read export directory: " + folder.getPath());
        }

        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                continue;
            }
            zos.putNextEntry(new ZipEntry(parentFolder + "/" + file.getName()));
            Files.copy(file.toPath(), zos);
            zos.closeEntry();
        }
    }

    public static void addFilesRecursively(File base, File dir, List<Map<String, String>> files) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                addFilesRecursively(base, entry, files);
            } else {
                String relativePath = base.toURI().relativize(entry.toURI()).getPath();
                files.add(Map.of(
                    "name", entry.getName(),
                    "path", base.getName() + "/" + relativePath,
                    "size", String.valueOf(entry.length()),
                    "timestamp", base.getName()
                ));
            }
        }
    }

    public static boolean isAlreadyCompressedFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".bz2")
            || lower.endsWith(".xz") || lower.endsWith(".7z") || lower.endsWith(".rar")
            || lower.endsWith(".xlsx") || lower.endsWith(".png") || lower.endsWith(".jpg")
            || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".pdf");
    }

    public static boolean shouldZipSingleFile(File file, String mode, long thresholdBytes) {
        return "always".equals(mode)
            || ("auto".equals(mode) && file.length() >= thresholdBytes && !isAlreadyCompressedFile(file.getName()));
    }

    public static boolean isExpectedClientDisconnect(Exception e) {
        Throwable t = e;
        while (t != null) {
            String name = t.getClass().getName();
            String msg = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (name.contains("EofException") || name.contains("TimeoutException")
                    || msg.contains("broken pipe") || msg.contains("connection reset")
                    || msg.contains("idle timeout")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
