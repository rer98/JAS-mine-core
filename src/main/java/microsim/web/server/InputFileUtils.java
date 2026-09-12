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
 * Safely resolves input paths, lists visible input files, classifies file types,
 * and replaces direct or zipped input files atomically before Build.
 *
 * @author ross richardson
 *
 */

/** Utility methods for listing, classifying, and atomically replacing simulation input files. */
public final class InputFileUtils {
    private InputFileUtils() {}

    public static List<Map<String, Object>> listVisibleInputFiles(File inputDir) {
        List<Map<String, Object>> fileList = new ArrayList<>();
        if (!inputDir.exists()) return fileList;

        File[] files = inputDir.listFiles();
        if (files == null) return fileList;

        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            fileList.add(Map.of(
                "name", f.getName(),
                "size", f.length(),
                "type", classifyInputFile(f.getName())
            ));
        }
        return fileList;
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

    public static File resolveInputFile(String filename) throws IOException {
        return PathSafety.safeResolve("input", filename);
    }

    public static void replaceInputFile(File target, InputStream in) throws IOException {
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
                zis.transferTo(out);
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
                in.transferTo(out);
            }
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
