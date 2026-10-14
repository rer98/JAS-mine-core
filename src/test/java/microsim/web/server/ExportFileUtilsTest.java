package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for ExportFileUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by ExportFileUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class ExportFileUtilsTest {
    @org.junit.jupiter.api.io.TempDir Path temporaryDirectory;

    @Test
    void symlinkFilesDirectoriesAndRootsAreRejectedWithoutExportingTargets() throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "FICTIONAL PRIVATE MARKER");
        for (String kind : List.of("file", "directory", "root")) {
            Path run = temporaryDirectory.resolve(kind);
            if (kind.equals("root")) Files.createSymbolicLink(run, outside);
            else {
                Files.createDirectory(run);
                Files.createSymbolicLink(run.resolve("link"), kind.equals("file") ? outside.resolve("private.txt") : outside);
            }
            var bytes = new java.io.ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                assertThrows(IOException.class, () -> ExportFileUtils.zipDirectory(run.toFile(), "run", zip));
            }
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                assertNull(zip.getNextEntry());
            }
            var files = new ArrayList<Map<String,String>>();
            assertThrows(IOException.class, () -> ExportFileUtils.addFilesRecursively(run.toFile(), run.toFile(), files));
            assertTrue(files.isEmpty());
        }
    }

    @Test
    void excessiveNestingAndUnsafeArchivePathsFailClosed() throws Exception {
        Path run = Files.createDirectory(temporaryDirectory.resolve("deep"));
        Path child = run;
        for (int i = 0; i < 66; i++) child = Files.createDirectory(child.resolve("child"));
        Files.writeString(child.resolve("data.txt"), "fictional");
        var files = new ArrayList<Map<String,String>>();
        assertThrows(IOException.class, () -> ExportFileUtils.addFilesRecursively(run.toFile(), run.toFile(), files));
        try (var zip = new ZipOutputStream(java.io.OutputStream.nullOutputStream())) {
            assertThrows(IOException.class, () -> ExportFileUtils.zipDirectory(run.toFile(), "../escape", zip));
            assertThrows(IOException.class, () -> ExportFileUtils.zipDirectory(run.toFile(), "run", zip));
        }
    }

    @Test
    void linkedAncestorsAndDirectoryCyclesCannotBypassTraversalChecks() throws Exception {
        Path real = Files.createDirectories(temporaryDirectory.resolve("real/run"));
        Files.writeString(real.resolve("private.txt"), "fictional private");
        Path alias = temporaryDirectory.resolve("alias");
        Files.createSymbolicLink(alias, real.getParent());
        Path cycle = Files.createDirectory(temporaryDirectory.resolve("cycle"));
        Files.createSymbolicLink(cycle.resolve("again"), cycle);
        for (Path run : List.of(alias.resolve("run"), cycle)) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                var files = new ArrayList<Map<String,String>>();
                assertThrows(IOException.class, () -> ExportFileUtils.addFilesRecursively(run.toFile(), run.toFile(), files));
                try (var zip = new ZipOutputStream(java.io.OutputStream.nullOutputStream())) {
                    assertThrows(IOException.class, () -> ExportFileUtils.zipDirectory(run.toFile(), "run", zip));
                }
            });
        }
    }

    @Test
    public void compressedExtensionsAreDetectedCaseInsensitively() {
        assertTrue(ExportFileUtils.isAlreadyCompressedFile("archive.ZIP"));
        assertTrue(ExportFileUtils.isAlreadyCompressedFile("image.png"));
        assertTrue(ExportFileUtils.isAlreadyCompressedFile("workbook.xlsx"));
        assertFalse(ExportFileUtils.isAlreadyCompressedFile("data.csv"));
    }

    @Test
    public void singleFileZipDecisionRespectsModeThresholdAndCompression() throws Exception {
        Path dir = Files.createTempDirectory("export-utils-test");
        File csv = dir.resolve("data.csv").toFile();
        Files.writeString(csv.toPath(), "abcdef", StandardCharsets.UTF_8);
        File zip = dir.resolve("data.zip").toFile();
        Files.writeString(zip.toPath(), "abcdef", StandardCharsets.UTF_8);

        assertTrue(ExportFileUtils.shouldZipSingleFile(csv, "always", 100));
        assertFalse(ExportFileUtils.shouldZipSingleFile(csv, "never", 1));
        assertTrue(ExportFileUtils.shouldZipSingleFile(csv, "auto", 3));
        assertFalse(ExportFileUtils.shouldZipSingleFile(csv, "auto", 100));
        assertFalse(ExportFileUtils.shouldZipSingleFile(zip, "auto", 3));
    }

    @Test
    public void addFilesRecursivelyRecordsRelativePathsAndMetadata() throws Exception {
        Path basePath = Files.createTempDirectory("run-20260722");
        Files.writeString(basePath.resolve("a.csv"), "a", StandardCharsets.UTF_8);
        Files.createDirectories(basePath.resolve("nested"));
        Files.writeString(basePath.resolve("nested/b.csv"), "bb", StandardCharsets.UTF_8);

        List<Map<String, String>> files = new ArrayList<>();
        ExportFileUtils.addFilesRecursively(basePath.toFile(), basePath.toFile(), files);

        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(m -> m.get("path").endsWith("/a.csv") && m.get("name").equals("a.csv")));
        assertTrue(files.stream().anyMatch(m -> m.get("path").endsWith("/nested/b.csv") && m.get("name").equals("b.csv")));
        assertTrue(files.stream().allMatch(m -> m.containsKey("size") && m.get("timestamp").equals(basePath.getFileName().toString())));
    }

    @Test
    public void zipDirectoryCreatesNestedEntries() throws Exception {
        Path basePath = Files.createTempDirectory("zip-run");
        Files.writeString(basePath.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.createDirectories(basePath.resolve("nested"));
        Files.writeString(basePath.resolve("nested/b.txt"), "b", StandardCharsets.UTF_8);
        Path zipPath = Files.createTempFile("export-utils", ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ExportFileUtils.zipDirectory(basePath.toFile(), "run", zos);
        }

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            assertNotNull(zip.getEntry("run/a.txt"));
            assertNotNull(zip.getEntry("run/nested/b.txt"));
        }
    }

    @Test
    public void expectedClientDisconnectDetectsCommonMessagesAndCauses() {
        assertTrue(ExportFileUtils.isExpectedClientDisconnect(new IOException("Broken pipe")));
        assertTrue(ExportFileUtils.isExpectedClientDisconnect(new RuntimeException("wrapper", new IOException("Connection reset by peer"))));
        assertFalse(ExportFileUtils.isExpectedClientDisconnect(new IOException("disk full")));
    }
}
