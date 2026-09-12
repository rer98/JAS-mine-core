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
