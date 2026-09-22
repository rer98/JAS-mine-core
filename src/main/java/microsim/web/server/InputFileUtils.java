package microsim.web.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Input-file helpers for JAS-mine Web model inputs.
 * Safely resolves nested input paths, lists visible files and directories,
 * classifies file types, and replaces direct or zipped files atomically before Build.
 *
 * @author ross richardson
 *
 */

/** Utility methods for browsing, classifying, and atomically replacing simulation input files. */
public final class InputFileUtils {
    private InputFileUtils() {}

    public static List<Map<String, Object>> listVisibleInputEntries(File inputRoot, String relativePath) throws IOException {
        File root = inputRoot.getCanonicalFile();
        File directory = resolveInputDirectory(root, relativePath);
        List<Map<String, Object>> entries = new ArrayList<>();
        File[] children = directory.listFiles();
        if (children == null) return entries;

        Arrays.sort(children, Comparator
            .comparing((File file) -> !file.isDirectory())
            .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            if (!isVisibleEntry(root, child)) continue;
            entries.add(inputEntry(root, child));
        }
        return entries;
    }

    public static List<Map<String, Object>> listVisibleInputFilesRecursively(File inputRoot) throws IOException {
        File root = inputRoot.getCanonicalFile();
        List<Map<String, Object>> files = new ArrayList<>();
        if (!root.exists() || !root.isDirectory()) return files;
        collectVisibleFiles(root, root, files);
        files.sort(Comparator.comparing(entry -> (String) entry.get("path"), String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private static void collectVisibleFiles(File root, File directory, List<Map<String, Object>> files) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            if (!isVisibleEntry(root, child)) continue;
            if (child.isDirectory()) collectVisibleFiles(root, child, files);
            else if (child.isFile()) files.add(inputEntry(root, child));
        }
    }

    private static File resolveInputDirectory(File root, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            if (!root.exists() || !root.isDirectory()) {
                throw new IllegalArgumentException("Input directory not found");
            }
            return root;
        }
        File resolved = resolveInputPath(root, relativePath);
        if (resolved == null) throw new IllegalArgumentException("Invalid input directory path");
        if (!resolved.exists() || !resolved.isDirectory()) {
            throw new IllegalArgumentException("Input directory not found");
        }
        return resolved;
    }

    private static boolean isVisibleEntry(File root, File entry) throws IOException {
        if (entry.getName().startsWith(".") || Files.isSymbolicLink(entry.toPath())) return false;
        File canonical = entry.getCanonicalFile();
        return canonical.toPath().startsWith(root.toPath()) && (canonical.isFile() || canonical.isDirectory());
    }

    private static Map<String, Object> inputEntry(File root, File entry) throws IOException {
        String path = root.toPath().relativize(entry.getCanonicalFile().toPath()).toString()
            .replace(File.separatorChar, '/');
        boolean directory = entry.isDirectory();
        return Map.of(
            "name", entry.getName(),
            "path", path,
            "kind", directory ? "directory" : "file",
            "type", directory ? "directory" : classifyInputFile(entry.getName()),
            "size", directory ? 0L : entry.length()
        );
    }

    public static String classifyInputFile(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".db") || lower.endsWith(".db.zip")) return "database";
        if (isExcelFile(filename)) return "excel";
        return "other";
    }

    public static boolean isExcelFile(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xls") || lower.endsWith(".xlsx");
    }

    public static boolean isAllowedByDetailedDataPolicy(String filename, boolean detailedDataAccessAllowed) {
        return detailedDataAccessAllowed || isExcelFile(filename);
    }

    public static File resolveInputPath(File inputRoot, String relativePath) throws IOException {
        File root = inputRoot.getCanonicalFile();
        File resolved = PathSafety.safeResolveDescendant(root, relativePath);
        if (resolved == null) return null;

        Path current = root.toPath();
        for (Path segment : Path.of(relativePath)) {
            String name = segment.toString();
            if (name.startsWith(".")) return null;
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) return null;
        }
        return resolved;
    }

    public static File resolveInputFile(String relativePath) throws IOException {
        return resolveInputPath(new File("input"), relativePath);
    }

    public static void replaceInputFile(File target, InputStream in) throws IOException {
        in = UploadLimits.bounded(in);
        if (target.getName().endsWith(".db")) replaceDatabaseFromZip(target, in);
        else replaceDirect(target, in);
    }

    private static void replaceDatabaseFromZip(File target, InputStream in) throws IOException {
        String filename = target.getName();
        Path temp = Files.createTempFile(target.toPath().getParent(), filename, ".upload");
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) throw new IllegalArgumentException("Received empty zip file");
            if (entry.isDirectory() || !entry.getName().equals(filename)) {
                throw new IllegalArgumentException("Zip entry must match target filename");
            }
            try (var out = Files.newOutputStream(temp)) {
                UploadLimits.copy(zis, out, UploadLimits.available(temp.getParent(), UploadLimits.FILE_BYTES));
            }
            if (zis.getNextEntry() != null) {
                throw new IllegalArgumentException("Zip upload must contain exactly one file");
            }
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void replaceDirect(File target, InputStream in) throws IOException {
        Path temp = Files.createTempFile(target.toPath().getParent(), target.getName(), ".upload");
        try {
            try (var out = Files.newOutputStream(temp)) {
                UploadLimits.copy(in, out, UploadLimits.available(temp.getParent(), UploadLimits.FILE_BYTES));
            }
            WorkbookBudget.validate(temp, target.getName());
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
