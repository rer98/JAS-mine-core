package microsim.web.server;

/* (C) Copyright 2026, by Ross Richardson
 * Oversized CSV diagnostic rejection, work-slot release and valid follow-up reads.
 * @author ross richardson
 */
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticBudgetTest {
    @TempDir Path directory;

    @Test void oversizedFieldsAndResultsFailWithoutHoldingSlots() throws Exception {
        Path file = directory.resolve("rows.csv");
        Files.writeString(file, "value\n" + "x".repeat(65537) + "\n");
        for (int i = 0; i < 3; i++)
            assertThrows(DiagnosticLimitException.class, () -> TabularDataUtils.rows(file.toFile(), ',', 0, 10, true, false));
        Files.writeString(file, "value\n" + ("x".repeat(32000) + "\n").repeat(70));
        assertThrows(DiagnosticLimitException.class, () -> TabularDataUtils.rows(file.toFile(), ',', 0, 100, true, false));
        assertEquals(1, TabularDataUtils.rows(file.toFile(), ',', 0, 1, true, false).get("returned"));
    }

    @Test void concurrentScansRejectInsteadOfQueuing() throws Exception {
        Path file = directory.resolve("rows.csv");
        Files.writeString(file, "value\n1\n");
        try (var first = DiagnosticReader.open(file, ','); var second = DiagnosticReader.open(file, ',')) {
            assertThrows(DiagnosticLimitException.class, () -> DiagnosticReader.open(file, ','));
        }
        assertEquals(1, TabularDataUtils.rows(file.toFile(), ',', 0, 10, true, false).get("returned"));
    }
}
