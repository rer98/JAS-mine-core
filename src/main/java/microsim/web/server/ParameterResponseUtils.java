package microsim.web.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Parameter endpoint response-shaping helpers for JAS-mine Web.
 * Builds response maps for model/collector parameter lists and parameter-history results
 * without reordering the parameter arrays supplied by introspection.
 *
 * @author ross richardson
 *
 */

/** Shared response shapes for simulation parameter endpoints. */
public final class ParameterResponseUtils {
    private ParameterResponseUtils() {}

    public static Map<String, Object> parameterLists(
            List<Map<String, Object>> modelParameters,
            List<Map<String, Object>> collectorParameters) {
        return Map.of(
            "modelParameters", modelParameters,
            "collectorParameters", collectorParameters
        );
    }

    public static Map<String, Object> noParameterHistory(String timestamp) {
        return Map.of(
            "found", false,
            "timestamp", timestamp,
            "file", "GUIparameters.csv",
            "message", "No GUIparameters.csv file found for this run"
        );
    }

    public static Map<String, Object> foundParameterHistory(Map<String, Object> rowsResult, String timestamp) {
        Map<String, Object> result = new LinkedHashMap<>(rowsResult);
        result.remove("headerIncluded");
        result.put("found", true);
        result.put("timestamp", timestamp);
        return result;
    }
}
