package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for TabularDataUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by TabularDataUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class TabularDataUtilsTest {
    @Test
    public void columnsReturnsHeaderAndPreviewRows() throws Exception {
        File file = sampleCsv();

        Map<String, Object> res = TabularDataUtils.columns(file, ',', 2);

        assertEquals(file.getName(), res.get("file"));
        assertEquals(List.of("name", "age", "score"), res.get("columns"));
        assertEquals(List.of(List.of("Alice", "10", "1.5"), List.of("Bob", "20", "2.5")), res.get("previewRows"));
        assertEquals(",", res.get("delimiter"));
    }

    @Test
    public void columnSummaryCountsMissingNumericAndTopValues() throws Exception {
        File file = sampleCsv();

        Map<String, Object> age = TabularDataUtils.columnSummary(file, ',', "age", 3, 100);
        assertEquals(4L, age.get("totalRows"));
        assertEquals(0L, age.get("missing"));
        assertEquals(4L, age.get("numericCount"));
        assertEquals(10.0, (Double) age.get("min"), 0.0001);
        assertEquals(40.0, (Double) age.get("max"), 0.0001);
        assertEquals(25.0, (Double) age.get("mean"), 0.0001);

        Map<String, Object> score = TabularDataUtils.columnSummary(file, ',', "score", 3, 100);
        assertEquals(1L, score.get("missing"));
        assertEquals(3L, score.get("numericCount"));

        Map<String, Object> name = TabularDataUtils.columnSummary(file, ',', "name", 1, 100);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topValues = (List<Map<String, Object>>) name.get("topValues");
        assertEquals("Alice", topValues.get(0).get("value"));
        assertEquals(2L, topValues.get(0).get("count"));
    }

    @Test
    public void columnSummaryReportsMissingColumnWithColumnList() throws Exception {
        try {
            TabularDataUtils.columnSummary(sampleCsv(), ',', "missing", 3, 100);
            fail("Expected MissingColumnException");
        } catch (TabularDataUtils.MissingColumnException e) {
            assertEquals("Column not found: missing", e.getMessage());
            assertEquals(List.of("name", "age", "score"), e.getColumns());
        }
    }

    @Test
    public void sampleRowsReturnsAllRowsWhenLimitExceedsData() throws Exception {
        Map<String, Object> res = TabularDataUtils.sampleRows(sampleCsv(), ',', 10, 123L);

        assertEquals(4L, res.get("totalRows"));
        assertEquals(4, res.get("sampledRows"));
        assertEquals(123L, res.get("seed"));
        assertEquals(List.of("name", "age", "score"), res.get("columns"));
    }

    @Test
    public void findRowsSupportsHeaderFiltersAndHasMore() throws Exception {
        Map<String, Object> res = TabularDataUtils.findRows(
            sampleCsv(), ',', true,
            List.of(Map.of("column", "name", "op", "=", "value", "Alice")),
            1
        );

        assertEquals(1, res.get("returned"));
        assertEquals(true, res.get("hasMore"));
        assertEquals(3L, res.get("scannedRows"));
        assertEquals(List.of("name", "age", "score"), res.get("columns"));
    }

    @Test
    public void findRowsSupportsNumericColumnIndexesWhenNoHeader() throws Exception {
        Map<String, Object> res = TabularDataUtils.findRows(
            sampleNoHeaderCsv(), ',', false,
            List.of(Map.of("column", "1", "op", ">=", "value", "30")),
            5
        );

        assertEquals(2, res.get("returned"));
        assertEquals(List.of(), res.get("columns"));
        assertEquals(false, res.get("hasMore"));
    }

    @Test
    public void rowsReturnsRequestedWindowAndOptionalTotalCount() throws Exception {
        Map<String, Object> page = TabularDataUtils.rows(sampleCsvWithBlankLine(), ',', 1, 2, true, true);

        assertEquals(List.of("name", "age", "score"), page.get("columns"));
        assertEquals(List.of(List.of("Bob", "20", "2.5"), List.of("Alice", "30", "")), page.get("rows"));
        assertEquals(1L, page.get("offset"));
        assertEquals(2, page.get("returned"));
        assertEquals(2, page.get("limit"));
        assertEquals(true, page.get("headerIncluded"));
        assertEquals(true, page.get("hasMore"));
        assertEquals(4L, page.get("totalDataRows"));
    }

    private static File sampleCsv() throws Exception {
        Path p = Files.createTempFile("tabular-data", ".csv");
        Files.writeString(p, "name,age,score\nAlice,10,1.5\nBob,20,2.5\nAlice,30,\nCarol,40,4.5\n", StandardCharsets.UTF_8);
        return p.toFile();
    }

    private static File sampleCsvWithBlankLine() throws Exception {
        Path p = Files.createTempFile("tabular-data-blank", ".csv");
        Files.writeString(p, "name,age,score\nAlice,10,1.5\n\nBob,20,2.5\nAlice,30,\nCarol,40,4.5\n", StandardCharsets.UTF_8);
        return p.toFile();
    }

    private static File sampleNoHeaderCsv() throws Exception {
        Path p = Files.createTempFile("tabular-data-no-header", ".csv");
        Files.writeString(p, "Alice,10\nBob,20\nAlice,30\nCarol,40\n", StandardCharsets.UTF_8);
        return p.toFile();
    }
}
