package microsim.web.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import microsim.web.ChartProcessors;
import microsim.web.SimulationServer;

import javax.swing.JInternalFrame;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Chart endpoint response builder for the JAS-mine Web server.
 * Parses incremental chart cursors, dispatches registered GUI frames to chart processors,
 * returns browser-ready chart payloads, and logs unsupported visualisations once per frame.
 *
 * @author ross richardson
 *
 */

/** Helpers for turning registered Swing plotters into chart endpoint responses. */
public final class ChartResponseUtils {
    private static final Set<String> REPORTED_UNSUPPORTED_FRAMES = ConcurrentHashMap.newKeySet();

    private ChartResponseUtils() {}

    public static Map<String, Integer> parseSinceMap(String sinceParam, ObjectMapper mapper) {
        Map<String, Integer> sinceMap = new HashMap<>();
        if (sinceParam == null || sinceParam.isEmpty()) return sinceMap;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawMap = mapper.readValue(sinceParam, Map.class);
            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                if (entry.getValue() instanceof Number) {
                    sinceMap.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                }
            }
        } catch (Exception e) {
            // Invalid JSON — proceed with empty map (full-replacement behaviour).
        }
        return sinceMap;
    }

    public static List<JInternalFrame> discoverPlotters(List<JInternalFrame> registeredFrames) {
        return discoverPlotters(registeredFrames, SimulationServer::addLogMessage);
    }

    static List<JInternalFrame> discoverPlotters(List<JInternalFrame> registeredFrames, Consumer<String> unsupportedFrameLogger) {
        List<JInternalFrame> plotters = new ArrayList<>();
        for (JInternalFrame frame : registeredFrames) {
            boolean accepted = addIfProcessable(frame, plotters);
            if (!accepted) {
                int before = plotters.size();
                findPlottersRecursively(frame.getContentPane(), plotters, unsupportedFrameLogger);
                if (plotters.size() == before) reportUnsupportedFrame(frame, unsupportedFrameLogger);
            }
        }
        return plotters;
    }

    static void clearUnsupportedFrameReportsForTests() {
        REPORTED_UNSUPPORTED_FRAMES.clear();
    }

    public static Map<String, Object> buildChartResponse(List<JInternalFrame> registeredFrames, String sinceParam, ObjectMapper mapper) {
        return buildChartResponse(discoverPlotters(registeredFrames), parseSinceMap(sinceParam, mapper));
    }

    public static Map<String, Object> buildChartResponse(List<JInternalFrame> plotters, Map<String, Integer> sinceMap) {
        List<Map<String, Object>> charts = new ArrayList<>();
        for (JInternalFrame frame : plotters) {
            for (ChartProcessors.ChartProcessor processor : ChartProcessors.getProcessors()) {
                if (processor.canProcess(frame)) {
                    try {
                        int sinceIndex = sinceMap.getOrDefault(frame.getTitle(), 0);
                        charts.add(processor.process(frame, sinceIndex));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
        }
        return responseFromCharts(charts);
    }

    public static Map<String, Object> responseFromCharts(List<Map<String, Object>> charts) {
        Map<String, Integer> newIndices = new HashMap<>();
        for (Map<String, Object> chartInfo : charts) {
            Object nextIdx = chartInfo.get("nextIndex");
            if (nextIdx instanceof Integer) {
                newIndices.put((String) chartInfo.get("title"), (Integer) nextIdx);
            }
        }
        return Map.of("charts", charts, "newIndices", newIndices);
    }

    private static void findPlottersRecursively(Container container, List<JInternalFrame> plotters, Consumer<String> unsupportedFrameLogger) {
        for (Component comp : container.getComponents()) {
            boolean accepted = false;
            if (comp instanceof JInternalFrame) {
                JInternalFrame frame = (JInternalFrame) comp;
                accepted = addIfProcessable(frame, plotters);
                if (!accepted) {
                    int before = plotters.size();
                    findPlottersRecursively(frame.getContentPane(), plotters, unsupportedFrameLogger);
                    if (plotters.size() == before) reportUnsupportedFrame(frame, unsupportedFrameLogger);
                }
            }
            if (!accepted && !(comp instanceof JInternalFrame) && comp instanceof Container) {
                findPlottersRecursively((Container) comp, plotters, unsupportedFrameLogger);
            }
        }
    }

    private static boolean addIfProcessable(JInternalFrame frame, List<JInternalFrame> plotters) {
        for (ChartProcessors.ChartProcessor proc : ChartProcessors.getProcessors()) {
            if (proc.canProcess(frame)) {
                plotters.add(frame);
                return true;
            }
        }
        return false;
    }

    private static void reportUnsupportedFrame(JInternalFrame frame, Consumer<String> unsupportedFrameLogger) {
        if (unsupportedFrameLogger == null) return;
        String title = frame.getTitle() == null ? "" : frame.getTitle();
        String key = frame.getClass().getName() + "\n" + title;
        if (REPORTED_UNSUPPORTED_FRAMES.add(key)) {
            unsupportedFrameLogger.accept(
                "No JAS-mine Web chart processor currently exists for "
                + frame.getClass().getSimpleName() + ": \"" + title + "\""
            );
        }
    }
}
