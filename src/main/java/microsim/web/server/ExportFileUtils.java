package microsim.web.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.OpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.Channels;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_DEPTH = 64;
    private ExportFileUtils() {}

    public static void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        validateArchivePath(parentFolder);
        try (var directory = openDirectory(folder.toPath())) {
            walk(directory, "", 0, new int[1], (parent, name, relative, attributes) -> {
                Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                // Resolve relative to the held directory descriptor, not a path
                // that could change to a link after inspection.
                try (var channel = parent.newByteChannel(name, options);
                     var input = Channels.newInputStream(channel)) {
                    zos.putNextEntry(new ZipEntry(parentFolder + "/" + relative));
                    input.transferTo(zos);
                    zos.closeEntry();
                }
            });
        }
    }

    public static void addFilesRecursively(File base, File dir, List<Map<String, String>> files) throws IOException {
        Path root = base.toPath().toAbsolutePath().normalize();
        Path start = dir.toPath().toAbsolutePath().normalize();
        if (!start.startsWith(root)) throw new IOException("Export directory is outside its run");
        String prefix = root.relativize(start).toString().replace(File.separatorChar, '/');
        try (var directory = openDirectory(start)) {
            walk(directory, prefix, 0, new int[1], (parent, name, relative, attributes) -> {
                files.add(Map.of(
                    "name", name.toString(),
                    "path", base.getName() + "/" + relative,
                    "size", String.valueOf(attributes.size()),
                    "timestamp", base.getName()
                ));
            });
        }
    }

    @FunctionalInterface
    private interface FileAction {
        void accept(SecureDirectoryStream<Path> parent, Path name, String relative,
                    BasicFileAttributes attributes) throws IOException;
    }

    private static void walk(SecureDirectoryStream<Path> directory, String prefix, int depth,
                             int[] entries, FileAction action) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Export directory nesting exceeds its limit");
        for (Path entry : directory) {
            if (++entries[0] > MAX_ENTRIES) throw new IOException("Export contains too many entries");
            Path name = entry.getFileName();
            validateArchivePath(name.toString());
            var attributes = directory.getFileAttributeView(name, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            String relative = prefix.isEmpty() ? name.toString() : prefix + "/" + name;
            if (attributes.isSymbolicLink()) throw new IOException("Symbolic links cannot be exported");
            if (attributes.isDirectory()) {
                try (var child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    walk(child, relative, depth + 1, entries, action);
                }
            } else if (attributes.isRegularFile()) {
                action.accept(directory, name, relative, attributes);
            } else {
                throw new IOException("Only regular files and directories can be exported");
            }
        }
    }

    /** Open every component without following links, including the export root. */
    private static SecureDirectoryStream<Path> openDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        DirectoryStream<Path> opened = Files.newDirectoryStream(absolute.getRoot());
        if (!(opened instanceof SecureDirectoryStream<Path> current)) {
            opened.close();
            throw new IOException("Safe directory export is unavailable on this filesystem");
        }
        try {
            for (Path component : absolute) {
                var next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                try { current.close(); }
                catch (IOException error) {
                    try { next.close(); } catch (IOException close) { error.addSuppressed(close); }
                    throw error;
                }
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException error) {
            try { current.close(); } catch (IOException close) { error.addSuppressed(close); }
            throw error;
        }
    }

    private static void validateArchivePath(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":"))
            throw new IOException("Unsupported archive entry name");
        for (String segment : path.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IOException("Unsupported archive entry name");
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
