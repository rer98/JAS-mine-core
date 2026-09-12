package microsim.web.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Metadata and README file helpers for JAS-mine Web.
 * Safely resolves metadata files, parses metadata JSON, returns missing-metadata response shapes,
 * and reads model README content for browser and AI diagnostic tools.
 *
 * @author ross richardson
 *
 */

/** File helpers for model metadata and README endpoints. */
public final class MetadataFileUtils {
    private MetadataFileUtils() {}

    public static final class InvalidMetadataPathException extends Exception {
        public InvalidMetadataPathException(String message) { super(message); }
    }

    public static Map<String, Object> metadataJson(String filename, String missingMessage, ObjectMapper mapper)
            throws IOException, InvalidMetadataPathException {
        return metadataJson(new File("."), filename, missingMessage, mapper);
    }

    public static Map<String, Object> metadataJson(File baseDir, String filename, String missingMessage, ObjectMapper mapper)
            throws IOException, InvalidMetadataPathException {
        File metadataDir = new File(baseDir, "metadata").getCanonicalFile();
        File file = new File(metadataDir, filename).getCanonicalFile();
        if (!file.toPath().startsWith(metadataDir.toPath())) {
            throw new InvalidMetadataPathException("Invalid metadata path");
        }
        String path = "metadata/" + filename;
        if (!file.exists() || !file.isFile()) {
            return Map.of("found", false, "message", missingMessage);
        }
        Object data = mapper.readValue(file, Object.class);
        return Map.of("found", true, "path", path, "data", data);
    }

    public static String readReadme() throws IOException {
        return readReadme(new File("."));
    }

    public static String readReadme(File baseDir) throws IOException {
        File readme = new File(baseDir, "README.md");
        if (!readme.exists() || !readme.isFile()) {
            throw new FileNotFoundException("README.md not found");
        }
        return Files.readString(readme.toPath());
    }
}
