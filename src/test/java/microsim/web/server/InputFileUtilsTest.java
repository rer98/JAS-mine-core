package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for InputFileUtils.
 * Verifies safe hierarchical input browsing, detailed-data policy decisions,
 * and atomic direct or zipped file replacement.
 *
 * @author ross richardson
 *
 */

public class InputFileUtilsTest {
    @Test
    public void classifyInputFilesByExtension() {
        assertEquals("database", InputFileUtils.classifyInputFile("input.mv.db"));
        assertEquals("database", InputFileUtils.classifyInputFile("input.mv.db.zip"));
        assertEquals("excel", InputFileUtils.classifyInputFile("reg_schooling.xls"));
        assertEquals("excel", InputFileUtils.classifyInputFile("REG_SCHOOLING.XLSX"));
        assertEquals("other", InputFileUtils.classifyInputFile("notes.txt"));
    }

    @Test
    public void detailedDataPolicyAlwaysAllowsExcelAndOtherwiseFollowsSetting() {
        assertTrue(InputFileUtils.isAllowedByDetailedDataPolicy("scenario.xlsx", false));
        assertTrue(InputFileUtils.isAllowedByDetailedDataPolicy("scenario.xls", false));
        assertFalse(InputFileUtils.isAllowedByDetailedDataPolicy("input.mv.db", false));
        assertFalse(InputFileUtils.isAllowedByDetailedDataPolicy("population.csv", false));
        assertTrue(InputFileUtils.isAllowedByDetailedDataPolicy("input.mv.db", true));
        assertTrue(InputFileUtils.isAllowedByDetailedDataPolicy("population.csv", true));
    }

    @Test
    public void directoryListingSeparatesDirectoriesAndFilesWithRelativePaths() throws Exception {
        Path root = Files.createTempDirectory("input-file-utils-list");
        Path nested = Files.createDirectories(root.resolve("EUROMODoutput/training"));
        Files.writeString(root.resolve("b.txt"), "bb", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("A.xlsx"), "a", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".hidden"), "hidden", StandardCharsets.UTF_8);
        Files.writeString(nested.resolve("policy.xlsx"), "policy", StandardCharsets.UTF_8);

        List<Map<String, Object>> rootEntries = InputFileUtils.listVisibleInputEntries(root.toFile(), "");
        List<Map<String, Object>> nestedEntries = InputFileUtils.listVisibleInputEntries(root.toFile(), "EUROMODoutput/training");

        assertEquals(List.of("EUROMODoutput", "A.xlsx", "b.txt"), rootEntries.stream().map(e -> e.get("name")).toList());
        assertEquals("directory", rootEntries.get(0).get("kind"));
        assertEquals("EUROMODoutput", rootEntries.get(0).get("path"));
        assertEquals("file", rootEntries.get(1).get("kind"));
        assertEquals("excel", rootEntries.get(1).get("type"));
        assertEquals("EUROMODoutput/training/policy.xlsx", nestedEntries.get(0).get("path"));
    }

    @Test
    public void recursiveListingReturnsFilesOnlyAndSkipsHiddenTreesAndSymlinks() throws Exception {
        Path root = Files.createTempDirectory("input-file-utils-recursive");
        Path visible = Files.createDirectories(root.resolve("visible/nested"));
        Path hidden = Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve("root.xlsx"), "root", StandardCharsets.UTF_8);
        Files.writeString(visible.resolve("data.txt"), "data", StandardCharsets.UTF_8);
        Files.writeString(hidden.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.resolve("linked"), visible);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) {
            // Symlink creation may be unavailable on some test platforms.
        }

        List<Map<String, Object>> files = InputFileUtils.listVisibleInputFilesRecursively(root.toFile());

        assertEquals(List.of("root.xlsx", "visible/nested/data.txt"), files.stream().map(e -> e.get("path")).toList());
        assertTrue(files.stream().allMatch(e -> "file".equals(e.get("kind"))));
    }

    @Test
    public void nestedPathResolutionAllowsVisibleDescendantsAndRejectsTraversalOrHiddenPaths() throws Exception {
        Path root = Files.createTempDirectory("input-file-utils-path");
        Path file = Files.createDirectories(root.resolve("subdir")).resolve("config.xls");
        Files.writeString(file, "contents", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".hidden.xls"), "hidden", StandardCharsets.UTF_8);

        assertEquals(file.toFile().getCanonicalFile(), InputFileUtils.resolveInputPath(root.toFile(), "subdir/config.xls").getCanonicalFile());
        assertNull(InputFileUtils.resolveInputPath(root.toFile(), "../secret.xls"));
        assertNull(InputFileUtils.resolveInputPath(root.toFile(), "/tmp/secret.xls"));
        assertNull(InputFileUtils.resolveInputPath(root.toFile(), ".hidden.xls"));
        Path link = root.resolve("linked.xls");
        try {
            Files.createSymbolicLink(link, file);
            assertNull(InputFileUtils.resolveInputPath(root.toFile(), "linked.xls"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) {
            // Symlink creation may be unavailable on some test platforms.
        }
    }

    @Test
    public void replaceDirectWritesViaTemporaryFile() throws Exception {
        Path dir = Files.createTempDirectory("input-file-utils-direct");
        Path target = dir.resolve("config.xlsx");
        Files.writeString(target, "old", StandardCharsets.UTF_8);

        byte[] replacement = zip("xl/worksheets/sheet1.xml", "<worksheet><sheetData/></worksheet>");
        InputFileUtils.replaceInputFile(target.toFile(), new ByteArrayInputStream(replacement));

        assertArrayEquals(replacement, Files.readAllBytes(target));
    }

    @Test
    public void replaceDatabaseAcceptsSingleMatchingZipEntry() throws Exception {
        Path dir = Files.createTempDirectory("input-file-utils-db");
        Path target = dir.resolve("input.mv.db");
        Files.writeString(target, "old", StandardCharsets.UTF_8);

        InputFileUtils.replaceInputFile(target.toFile(), new ByteArrayInputStream(zip("input.mv.db", "new-db")));

        assertEquals("new-db", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    public void replaceDatabaseRejectsEmptyMismatchedOrMultiEntryZip() throws Exception {
        Path dir = Files.createTempDirectory("input-file-utils-bad-db");
        Path target = dir.resolve("input.mv.db");
        Files.writeString(target, "old", StandardCharsets.UTF_8);

        assertBadZip(target, emptyZip(), "Received empty zip file");
        assertBadZip(target, zip("other.mv.db", "bad"), "Zip entry must match target filename");
        assertBadZip(target, zipTwoEntries(), "Zip upload must contain exactly one file");
        assertEquals("old", Files.readString(target, StandardCharsets.UTF_8));
    }

    private static void assertBadZip(Path target, byte[] zipBytes, String message) throws Exception {
        try {
            InputFileUtils.replaceInputFile(target.toFile(), new ByteArrayInputStream(zipBytes));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }

    private static byte[] zip(String name, String contents) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(contents.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] emptyZip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream ignored = new ZipOutputStream(baos)) {}
        return baos.toByteArray();
    }

    private static byte[] zipTwoEntries() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("input.mv.db"));
            zos.write("first".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("extra.txt"));
            zos.write("extra".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
