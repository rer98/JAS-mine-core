package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for ParameterResponseUtils.
 * Verifies the focused JAS-mine Web helper behaviour implemented by ParameterResponseUtils
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class ParameterResponseUtilsTest {
    @Test
    public void parameterListsKeepsModelAndCollectorLists() {
        List<Map<String, Object>> modelParameters = new ArrayList<>();
        List<Map<String, Object>> collectorParameters = new ArrayList<>();
        modelParameters.add(Map.of("name", "alpha", "value", 1));
        collectorParameters.add(Map.of("name", "beta", "value", 2));

        Map<String, Object> res = ParameterResponseUtils.parameterLists(modelParameters, collectorParameters);

        assertSame(modelParameters, res.get("modelParameters"));
        assertSame(collectorParameters, res.get("collectorParameters"));
    }

    @Test
    public void noParameterHistoryMatchesMissingFileResponseShape() {
        assertEquals(
            Map.of(
                "found", false,
                "timestamp", "20260724110004",
                "file", "GUIparameters.csv",
                "message", "No GUIparameters.csv file found for this run"
            ),
            ParameterResponseUtils.noParameterHistory("20260724110004")
        );
    }

    @Test
    public void foundParameterHistoryAddsEndpointFieldsAndOmitsInternalHeaderFlag() {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("columns", List.of("time", "parameter", "value"));
        rows.put("rows", List.of(List.of("0", "speed", "10")));
        rows.put("headerIncluded", true);

        Map<String, Object> res = ParameterResponseUtils.foundParameterHistory(rows, "20260724110004");

        assertEquals(true, res.get("found"));
        assertEquals("20260724110004", res.get("timestamp"));
        assertEquals(List.of("time", "parameter", "value"), res.get("columns"));
        assertFalse(res.containsKey("headerIncluded"));
        assertTrue(rows.containsKey("headerIncluded"));
    }
}
