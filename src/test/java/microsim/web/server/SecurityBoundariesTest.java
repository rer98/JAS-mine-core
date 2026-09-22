/* (C) Copyright 2026, by Ross Richardson
 * Adversarial bounds and session-authentication regression checks.
 * @author ross richardson
 */
package microsim.web.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SecurityBoundariesTest {
    @TempDir Path dir;

    @Test void authenticationIsMandatoryAndSessionSpecific() {
        assertThrows(IllegalStateException.class, () -> new BackendAuth(Map.of()));
        var a = new BackendAuth(Map.of("JASMINE_BACKEND_SECRET", "a".repeat(43)));
        assertFalse(a.accepts(null)); assertFalse(a.accepts("b".repeat(43)));
        assertTrue(a.accepts("a".repeat(43)));
        assertTrue(new BackendAuth(Map.of("JASMINE_LOCAL_DEVELOPMENT", "true")).accepts(null));
    }

    @Test void oversizeCopyStopsBeforeWritingPastLimit() throws Exception {
        var out = new ByteArrayOutputStream();
        assertThrows(UploadLimits.LimitException.class, () -> UploadLimits.copy(new ByteArrayInputStream(new byte[1025]),out,1024));
        assertTrue(out.size() <= 1024);
    }

    @Test void compressedWorkbookIsRejectedBeforeReplacingOriginal() throws Exception {
        Path target = dir.resolve("workbook.xlsx"); Files.writeString(target, "original");
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("xl/padding.bin"));
            byte[] block = new byte[65536];
            for (int i=0; i<1025; i++) zip.write(block);
            zip.closeEntry();
        }
        assertThrows(UploadLimits.LimitException.class, () -> InputFileUtils.replaceInputFile(target.toFile(),new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals("original",Files.readString(target));
        Path renamed = dir.resolve("renamed.xls"); Files.write(renamed,bytes.toByteArray());
        assertThrows(UploadLimits.LimitException.class, () -> WorkbookBudget.validate(renamed,"renamed.xls"));
        Files.delete(renamed);
        try(var files=Files.list(dir)) { assertEquals(1,files.count()); }
    }

    @Test void boundedCsvHandlesQuotedHeaderAndRejectsUnlimitedFields() throws Exception {
        try (var csv = new CsvRecordReader(new StringReader("\"one,two\",three\n"),',',100,30,3)) {
            assertEquals(List.of("one,two","three"),csv.readRecord());
        }
        try (var csv = new CsvRecordReader(new StringReader("x".repeat(1000)),',',100,30,3)) {
            assertThrows(IOException.class,csv::readRecord);
        }
    }

    @Test void consoleHasByteAndUnterminatedLineBounds() throws Exception {
        var log = new SimulationLogBuffer(100000);
        try(var stream = log.capturingOutputStream(new PrintStream(OutputStream.nullOutputStream()))) {
            stream.write(new byte[1024*1024]); stream.write('\n');
        }
        assertTrue(log.poll(0).toString().contains("line truncated"));
        for (int i=0;i<600;i++) log.add("x".repeat(65000));
        assertTrue((Long)log.poll(0).get("firstIndex") > 0);
        assertEquals(true,log.poll(0).get("truncatedBefore"));
    }

    @Test void safeFeedDoesNotAcceptArbitraryPathsOrExceptionValues() {
        SafeDiagnostics.request("/simulation/private-record-value",500);
        SafeDiagnostics.request("/simulation/build",400);
        SafeDiagnostics.incident("private-record-value");
        String text=SafeDiagnostics.snapshot().toString();
        assertFalse(text.contains("private-record-value")); assertTrue(text.contains("rejected"));
    }
}
