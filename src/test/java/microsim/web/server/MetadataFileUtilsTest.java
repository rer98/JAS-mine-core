package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for MetadataFileUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by MetadataFileUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class MetadataFileUtilsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void metadataJsonReturnsParsedJsonWithStablePath() throws Exception {
        Path dir = Files.createTempDirectory("metadata-utils");
        Files.createDirectories(dir.resolve("metadata"));
        Files.writeString(dir.resolve("metadata/data-dictionary.json"), "{\"version\":3}", StandardCharsets.UTF_8);

        Map<String, Object> res = MetadataFileUtils.metadataJson(
            dir.toFile(), "data-dictionary.json", "missing", mapper
        );

        assertEquals(true, res.get("found"));
        assertEquals("metadata/data-dictionary.json", res.get("path"));
        assertEquals(3, ((Map<?, ?>) res.get("data")).get("version"));
    }

    @Test
    public void metadataJsonReturnsFoundFalseWhenFileMissing() throws Exception {
        Path dir = Files.createTempDirectory("metadata-utils-missing");

        Map<String, Object> res = MetadataFileUtils.metadataJson(
            dir.toFile(), "source-info.json", "No metadata/source-info.json file found for this model", mapper
        );

        assertEquals(false, res.get("found"));
        assertEquals("No metadata/source-info.json file found for this model", res.get("message"));
    }

    @Test
    public void metadataJsonRejectsTraversal() throws Exception {
        Path dir = Files.createTempDirectory("metadata-utils-traversal");
        Files.writeString(dir.resolve("outside.json"), "{}", StandardCharsets.UTF_8);

        try {
            MetadataFileUtils.metadataJson(dir.toFile(), "../outside.json", "missing", mapper);
            fail("Expected invalid path");
        } catch (MetadataFileUtils.InvalidMetadataPathException e) {
            assertEquals("Invalid metadata path", e.getMessage());
        }
    }

    @Test
    public void readReadmeReturnsContentOrFileNotFound() throws Exception {
        Path dir = Files.createTempDirectory("metadata-utils-readme");
        Files.writeString(dir.resolve("README.md"), "hello readme", StandardCharsets.UTF_8);
        assertEquals("hello readme", MetadataFileUtils.readReadme(dir.toFile()));

        Path missing = Files.createTempDirectory("metadata-utils-no-readme");
        try {
            MetadataFileUtils.readReadme(missing.toFile());
            fail("Expected missing README");
        } catch (java.io.FileNotFoundException e) {
            assertEquals("README.md not found", e.getMessage());
        }
    }
}
