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
 * Verifies the focused JAS-mine Web helper behaviour implemented by InputFileUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
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
    public void listVisibleInputFilesSortsAndSkipsDotFiles() throws Exception {
        Path dir = Files.createTempDirectory("input-file-utils-list");
        Files.writeString(dir.resolve("b.txt"), "bb", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("A.xlsx"), "a", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(".hidden"), "hidden", StandardCharsets.UTF_8);

        List<Map<String, Object>> files = InputFileUtils.listVisibleInputFiles(dir.toFile());

        assertEquals(2, files.size());
        assertEquals("A.xlsx", files.get(0).get("name"));
        assertEquals("excel", files.get(0).get("type"));
        assertEquals("b.txt", files.get(1).get("name"));
        assertEquals("other", files.get(1).get("type"));
    }

    @Test
    public void replaceDirectWritesViaTemporaryFile() throws Exception {
        Path dir = Files.createTempDirectory("input-file-utils-direct");
        Path target = dir.resolve("config.xls");
        Files.writeString(target, "old", StandardCharsets.UTF_8);

        InputFileUtils.replaceInputFile(target.toFile(), new ByteArrayInputStream("new".getBytes(StandardCharsets.UTF_8)));

        assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
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


    @Test
    public void resolveInputFileValidatesTraversalButAllowsMissingExistingChecksUpstream() throws Exception {
        Path input = Path.of("input");
        Files.createDirectories(input);
        Path file = input.resolve("config.xls");
        Files.writeString(file, "contents", StandardCharsets.UTF_8);
        try {
            assertEquals(file.toFile().getCanonicalFile(), InputFileUtils.resolveInputFile("config.xls").getCanonicalFile());
            File missing = InputFileUtils.resolveInputFile("missing.xls");
            assertNotNull(missing);
            assertFalse(missing.exists());
            assertNull(InputFileUtils.resolveInputFile("../secret.xls"));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(input);
        }
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
