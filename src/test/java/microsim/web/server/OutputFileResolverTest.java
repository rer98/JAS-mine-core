package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for OutputFileResolver.
 * Verifies the focused JAS-mine Web helper behaviour implemented by OutputFileResolver
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class OutputFileResolverTest {
    @Test
    public void stripTimestampPrefixOnlyRemovesMatchingLeadingTimestamp() {
        assertEquals("csv/Person.csv", OutputFileResolver.stripTimestampPrefix("20260724132330", "20260724132330/csv/Person.csv"));
        assertEquals("other/csv/Person.csv", OutputFileResolver.stripTimestampPrefix("20260724132330", "other/csv/Person.csv"));
        assertNull(OutputFileResolver.stripTimestampPrefix("20260724132330", null));
    }

    @Test
    public void invalidOrMissingRunDirectoryReturnsNulls() throws Exception {
        assertNull(OutputFileResolver.runDirectory("../bad"));
        OutputFileResolver.Result res = OutputFileResolver.outputFile("../bad", "x.csv");
        assertNull(res.runDir());
        assertNull(res.file());
    }

    @Test
    public void resolvesExistingFileUnderOutputTimestamp() throws Exception {
        Path output = Path.of("output", "20990101010101", "csv");
        Files.createDirectories(output);
        Path file = output.resolve("Person.csv");
        Files.writeString(file, "a,b\n1,2\n");
        try {
            OutputFileResolver.Result res = OutputFileResolver.outputFile("20990101010101", "20990101010101/csv/Person.csv");
            assertNotNull(res.runDir());
            assertEquals(file.toFile().getCanonicalFile(), res.file().getCanonicalFile());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(output);
            Files.deleteIfExists(output.getParent());
            deleteOutputRootIfEmpty();
        }
    }

    @Test
    public void rejectsTraversalOutsideRunDirectory() throws Exception {
        Path run = Path.of("output", "20990102020202");
        Files.createDirectories(run);
        try {
            OutputFileResolver.Result res = OutputFileResolver.outputFile("20990102020202", "../secret.csv");
            assertNotNull(res.runDir());
            assertNull(res.file());
        } finally {
            Files.deleteIfExists(run);
            deleteOutputRootIfEmpty();
        }
    }

    @Test
    public void tabularFileAcceptsCsvAndTsvButRejectsOtherFiles() throws Exception {
        Path run = Path.of("output", "20990103030303");
        Files.createDirectories(run);
        Path csv = run.resolve("people.csv");
        Path tsv = run.resolve("people.tsv");
        Path txt = run.resolve("people.txt");
        Files.writeString(csv, "a,b\n");
        Files.writeString(tsv, "a\tb\n");
        Files.writeString(txt, "plain\n");
        try {
            assertEquals(csv.toFile().getCanonicalFile(), OutputFileResolver.tabularFile("20990103030303", "people.csv").getCanonicalFile());
            assertEquals(tsv.toFile().getCanonicalFile(), OutputFileResolver.tabularFile("20990103030303", "20990103030303/people.tsv").getCanonicalFile());
            assertNull(OutputFileResolver.tabularFile("20990103030303", "people.txt"));
        } finally {
            Files.deleteIfExists(csv);
            Files.deleteIfExists(tsv);
            Files.deleteIfExists(txt);
            Files.deleteIfExists(run);
            deleteOutputRootIfEmpty();
        }
    }


    @Test
    public void outputFileResolvesGuiParametersCsv() throws Exception {
        Path run = Path.of("output", "20990104040404");
        Files.createDirectories(run);
        Path file = run.resolve("GUIparameters.csv");
        Files.writeString(file, "simulationTime,parameter,value\n0,p,1\n");
        try {
            OutputFileResolver.Result res = OutputFileResolver.outputFile("20990104040404", "GUIparameters.csv");
            assertNotNull(res.runDir());
            assertEquals(file.toFile().getCanonicalFile(), res.file().getCanonicalFile());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(run);
            deleteOutputRootIfEmpty();
        }
    }
    private static void deleteOutputRootIfEmpty() {
        File outputRoot = new File("output");
        if (outputRoot.isDirectory() && outputRoot.list().length == 0) outputRoot.delete();
    }
}
