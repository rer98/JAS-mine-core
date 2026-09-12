package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for PathSafety.
 * Verifies the focused JAS-mine Web helper behaviour implemented by PathSafety
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class PathSafetyTest {
    @Test
    public void safeTimestampAllowsOnlyExpectedDirectoryNames() {
        assertTrue(PathSafety.isSafeTimestamp("20260720_154700"));
        assertTrue(PathSafety.isSafeTimestamp("2026-07-20"));
        assertFalse(PathSafety.isSafeTimestamp(""));
        assertFalse(PathSafety.isSafeTimestamp("../output"));
        assertFalse(PathSafety.isSafeTimestamp("2026/07/20"));
    }

    @Test
    public void safeResolveRejectsTraversalAndSeparators() throws Exception {
        File base = Files.createTempDirectory("pathsafety").toFile();
        assertNotNull(PathSafety.safeResolve(base.getPath(), "input.csv"));
        assertNull(PathSafety.safeResolve(base.getPath(), "../input.csv"));
        assertNull(PathSafety.safeResolve(base.getPath(), "subdir/input.csv"));
        assertNull(PathSafety.safeResolve(base.getPath(), "subdir\\input.csv"));
    }

    @Test
    public void safeResolveDescendantAllowsSubdirectoriesButRejectsTraversal() throws Exception {
        File base = Files.createTempDirectory("pathsafety-desc").toFile();
        assertNotNull(PathSafety.safeResolveDescendant(base, "subdir/file.csv"));
        assertNull(PathSafety.safeResolveDescendant(base, "../file.csv"));
        assertNull(PathSafety.safeResolveDescendant(base, "/tmp/file.csv"));
        assertNull(PathSafety.safeResolveDescendant(base, "subdir\\file.csv"));
    }
}
