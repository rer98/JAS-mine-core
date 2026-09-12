package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for TabularExportUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by TabularExportUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class TabularExportUtilsTest {
    @Test
    public void escapeCsvQuotesOnlyWhenNeeded() {
        assertEquals("plain", TabularExportUtils.escapeCsv("plain"));
        assertEquals("", TabularExportUtils.escapeCsv(null));
        assertEquals("\"hello, world\"", TabularExportUtils.escapeCsv("hello, world"));
        assertEquals("\"he said \"\"hi\"\"\"", TabularExportUtils.escapeCsv("he said \"hi\""));
        assertEquals("\"line one\nline two\"", TabularExportUtils.escapeCsv("line one\nline two"));
    }

    @Test
    public void parseLongQueryParamFallsBackForMissingOrMalformedValues() {
        assertEquals(12L, TabularExportUtils.parseLongQueryParam("12", 5L));
        assertEquals(12L, TabularExportUtils.parseLongQueryParam(" 12 ", 5L));
        assertEquals(5L, TabularExportUtils.parseLongQueryParam(null, 5L));
        assertEquals(5L, TabularExportUtils.parseLongQueryParam("", 5L));
        assertEquals(5L, TabularExportUtils.parseLongQueryParam("abc", 5L));
    }

    @Test
    public void delimiterCanBeExplicitOrInferredFromFilename() {
        assertEquals('|', TabularExportUtils.delimiterForFile(new File("data.csv"), "|"));
        assertEquals(',', TabularExportUtils.delimiterForFile(new File("data.csv"), null));
        assertEquals('\t', TabularExportUtils.delimiterForFile(new File("data.tsv"), null));
    }

    @Test
    public void nextNonBlankRecordSkipsBlankLinesAndPreservesQuotedFields() throws Exception {
        String csv = "\nname,value\n\"hello, world\",\"line one\nline two\"\n";
        try (CsvRecordReader reader = new CsvRecordReader(new StringReader(csv), ',')) {
            assertEquals(List.of("name", "value"), TabularExportUtils.nextNonBlankRecord(reader));
            assertEquals(List.of("hello, world", "line one\nline two"), TabularExportUtils.nextNonBlankRecord(reader));
            assertNull(TabularExportUtils.nextNonBlankRecord(reader));
        }
    }
}
