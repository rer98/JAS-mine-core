package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.JInternalFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for ChartResponseUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by ChartResponseUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class ChartResponseUtilsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void setUp() {
        ChartResponseUtils.clearUnsupportedFrameReportsForTests();
    }

    @Test
    public void parseSinceMapAcceptsNumericValuesOnly() {
        Map<String, Integer> res = ChartResponseUtils.parseSinceMap(
            "{\"A\":5,\"B\":2.9,\"C\":\"ignored\"}", mapper
        );

        assertEquals(2, res.size());
        assertEquals(Integer.valueOf(5), res.get("A"));
        assertEquals(Integer.valueOf(2), res.get("B"));
        assertFalse(res.containsKey("C"));
    }

    @Test
    public void parseSinceMapTreatsMissingOrInvalidJsonAsEmpty() {
        assertTrue(ChartResponseUtils.parseSinceMap(null, mapper).isEmpty());
        assertTrue(ChartResponseUtils.parseSinceMap("", mapper).isEmpty());
        assertTrue(ChartResponseUtils.parseSinceMap("not json", mapper).isEmpty());
    }

    @Test
    public void responseFromChartsCollectsIntegerNextIndices() {
        List<Map<String, Object>> charts = List.of(
            Map.of("title", "A", "nextIndex", 10),
            Map.of("title", "B", "nextIndex", "ignored"),
            Map.of("title", "C")
        );

        Map<String, Object> res = ChartResponseUtils.responseFromCharts(charts);

        assertSame(charts, res.get("charts"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> newIndices = (Map<String, Integer>) res.get("newIndices");
        assertEquals(Map.of("A", 10), newIndices);
    }

    @Test
    public void discoverPlottersLogsUnsupportedFrameOncePerClassAndTitle() {
        List<String> messages = new ArrayList<>();
        JInternalFrame unsupported = new JInternalFrame("Age distribution");

        assertTrue(ChartResponseUtils.discoverPlotters(List.of(unsupported), messages::add).isEmpty());
        assertEquals(List.of(
            "No JAS-mine Web chart processor currently exists for JInternalFrame: \"Age distribution\""
        ), messages);

        assertTrue(ChartResponseUtils.discoverPlotters(List.of(unsupported), messages::add).isEmpty());
        assertEquals(1, messages.size());
    }

    @Test
    public void discoverPlottersLogsSameUnsupportedClassAgainForDifferentTitle() {
        List<String> messages = new ArrayList<>();

        ChartResponseUtils.discoverPlotters(List.of(new JInternalFrame("Age distribution")), messages::add);
        ChartResponseUtils.discoverPlotters(List.of(new JInternalFrame("Income distribution")), messages::add);

        assertEquals(2, messages.size());
        assertEquals(
            "No JAS-mine Web chart processor currently exists for JInternalFrame: \"Income distribution\"",
            messages.get(1)
        );
    }
}
