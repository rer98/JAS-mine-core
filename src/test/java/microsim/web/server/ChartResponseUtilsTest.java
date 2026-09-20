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
            Map.of("id", "chart-1", "title", "A", "nextIndex", 10),
            Map.of("id", "chart-2", "title", "A", "nextIndex", 20),
            Map.of("title", "C")
        );

        Map<String, Object> res = ChartResponseUtils.responseFromCharts(charts);

        assertSame(charts, res.get("charts"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> newIndices = (Map<String, Integer>) res.get("newIndices");
        assertEquals(Map.of("chart-1", 10, "chart-2", 20), newIndices);
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
    @Test
    @SuppressWarnings("unchecked")
    public void duplicateTitlesHaveIndependentCursorsAndRebuiltFramesHaveFreshIds() throws Exception {
        var first = new microsim.gui.plot.TimeSeriesSimulationPlotter("Same title", "Value");
        var second = new microsim.gui.plot.TimeSeriesSimulationPlotter("Same title", "Value");
        var field = first.getClass().getDeclaredField("dataset");
        field.setAccessible(true);
        for (var frame : List.of(first, second)) {
            var series = new org.jfree.data.xy.XYSeries("Values");
            series.add(2019, 1);
            series.add(2020, 2);
            ((org.jfree.data.xy.XYSeriesCollection) field.get(frame)).addSeries(series);
        }
        String firstId = ChartResponseUtils.chartId(first);
        String secondId = ChartResponseUtils.chartId(second);
        assertNotEquals(firstId, secondId);
        first.setTitle("Renamed");
        assertEquals(firstId, ChartResponseUtils.chartId(first));
        first.setTitle("Same title");
        var response = ChartResponseUtils.buildChartResponse(List.of(first, second),
                Map.of(firstId, 1, secondId, 2));
        var charts = (List<Map<String, Object>>) response.get("charts");
        assertEquals(2, charts.size());
        var firstSeries = (List<Map<String, Object>>) charts.get(0).get("series");
        var secondSeries = (List<Map<String, Object>>) charts.get(1).get("series");
        assertEquals(1, ((List<?>) firstSeries.get(0).get("x" )).size());
        assertEquals(0, ((List<?>) secondSeries.get(0).get("x" )).size());
        assertEquals(Map.of(firstId, 2, secondId, 2), response.get("newIndices"));
        var rebuilt = new microsim.gui.plot.TimeSeriesSimulationPlotter("Same title", "Value");
        assertNotEquals(firstId, ChartResponseUtils.chartId(rebuilt));
        assertNotEquals(secondId, ChartResponseUtils.chartId(rebuilt));
    }

}
