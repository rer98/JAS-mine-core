/* (C) Copyright 2026, by Ross Richardson
 * Candidate upload bounds, path protection and preservation on failed writes.
 * @author ross richardson
 */
package microsim.web.server;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class StartupUploadsTest {
    @TempDir Path root;
    @Test void traversalAndOversizeCannotChangeExistingInputs() throws Exception {
        Files.writeString(root.resolve("data.csv"), "old");
        assertThrows(IllegalArgumentException.class, () -> StartupUploads.store(root,"../outside",new ByteArrayInputStream(new byte[1]),10,20,0));
        assertThrows(IOException.class, () -> StartupUploads.store(root,"data.csv",new ByteArrayInputStream(new byte[11]),10,20,0));
        assertEquals("old", Files.readString(root.resolve("data.csv")));
        try (var files = Files.list(root)) { assertEquals(1,files.count()); }
        StartupUploads.store(root,"data.csv",new ByteArrayInputStream("new".getBytes()),10,3,0);
        assertEquals("new", Files.readString(root.resolve("data.csv")));
    }
    @Test void symlinksAndEmptyFilesAreRejected() throws Exception {
        Files.createSymbolicLink(root.resolve("link"), root.getParent());
        assertThrows(IllegalArgumentException.class, () -> StartupUploads.store(root,"link/other",new ByteArrayInputStream(new byte[1]),10,20,0));
        assertThrows(IOException.class, () -> StartupUploads.store(root,"empty",new ByteArrayInputStream(new byte[0]),10,20,0));
        assertFalse(Files.exists(root.resolve("empty")));
    }
}
