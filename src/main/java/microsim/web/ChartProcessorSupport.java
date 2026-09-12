package microsim.web;

import org.jfree.data.xy.XYSeriesCollection;

import javax.swing.JInternalFrame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Shared support utilities for converting JAS-mine GUI charts to web data.
 * Contains reflection caches, chart metadata helpers, axis-title extraction,
 * ChartPanel discovery, series-shape checks, and layered-surface sentinel values used by ChartProcessors.
 *
 * @author ross richardson
 *
 */

/** Shared helper logic for chart processors. */
final class ChartProcessorSupport {
    static final int GRID_EMPTY_COLOR_VALUE = Integer.MIN_VALUE;

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static record FieldKey(Class<?> clazz, String fieldName) {}
    private static final Map<FieldKey, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private ChartProcessorSupport() {}

    static void clearCachesForTests() {
        METHOD_CACHE.clear();
        FIELD_CACHE.clear();
    }

    static Method getCachedMethod(Class<?> cls, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        String key = cls.getName() + "#" + name + Arrays.toString(parameterTypes);
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;
        Method method = cls.getMethod(name, parameterTypes);
        METHOD_CACHE.put(key, method);
        return method;
    }

    static Map<String, Object> createChartInfo(String title, String type) {
        Map<String, Object> chartInfo = new HashMap<>();
        chartInfo.put("title", title);
        chartInfo.put("type", type);
        return chartInfo;
    }

    static void finalizeChartInfo(Map<String, Object> chartInfo, JInternalFrame frame,
            List<Map<String, Object>> seriesList) {
        chartInfo.put("series", seriesList);

        Map<String, String> axisTitles = getAxisTitles(frame);

        if (!chartInfo.containsKey("xAxisTitle") || "".equals(chartInfo.get("xAxisTitle"))) {
            String xTitle = axisTitles.get("xAxisTitle");
            if (xTitle != null && !xTitle.isEmpty()) {
                chartInfo.put("xAxisTitle", xTitle);
            }
        }
        if (!chartInfo.containsKey("yAxisTitle") || "".equals(chartInfo.get("yAxisTitle"))) {
            String yTitle = axisTitles.get("yAxisTitle");
            if (yTitle != null && !yTitle.isEmpty()) {
                chartInfo.put("yAxisTitle", yTitle);
            }
        }
    }

    static Map<String, String> getAxisTitles(JInternalFrame plotter) {
        Map<String, String> titles = new HashMap<>();
        titles.put("xAxisTitle", "");
        titles.put("yAxisTitle", "");

        try {
            org.jfree.chart.ChartPanel chartPanel = findChartPanel(plotter.getContentPane());
            if (chartPanel != null) {
                org.jfree.chart.JFreeChart chart = chartPanel.getChart();
                if (chart != null) {
                    org.jfree.chart.plot.Plot plot = chart.getPlot();

                    if (plot instanceof org.jfree.chart.plot.XYPlot) {
                        org.jfree.chart.plot.XYPlot xyPlot = (org.jfree.chart.plot.XYPlot) plot;
                        if (xyPlot.getDomainAxis() != null && xyPlot.getDomainAxis().getLabel() != null) {
                            titles.put("xAxisTitle", xyPlot.getDomainAxis().getLabel());
                        }
                        if (xyPlot.getRangeAxis() != null && xyPlot.getRangeAxis().getLabel() != null) {
                            titles.put("yAxisTitle", xyPlot.getRangeAxis().getLabel());
                        }
                    } else if (plot instanceof org.jfree.chart.plot.CategoryPlot) {
                        org.jfree.chart.plot.CategoryPlot catPlot = (org.jfree.chart.plot.CategoryPlot) plot;
                        if (catPlot.getDomainAxis() != null && catPlot.getDomainAxis().getLabel() != null) {
                            titles.put("xAxisTitle", catPlot.getDomainAxis().getLabel());
                        }
                        if (catPlot.getRangeAxis() != null && catPlot.getRangeAxis().getLabel() != null) {
                            titles.put("yAxisTitle", catPlot.getRangeAxis().getLabel());
                        }
                    }
                }
            }
        } catch (Exception e) {
            SimulationServer.addLogMessage("ChartProcessors: could not extract axis titles for '" + plotter.getTitle() + "' (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        return titles;
    }

    static org.jfree.chart.ChartPanel findChartPanel(java.awt.Container container) {
        for (java.awt.Component comp : container.getComponents()) {
            if (comp instanceof org.jfree.chart.ChartPanel) {
                return (org.jfree.chart.ChartPanel) comp;
            } else if (comp instanceof java.awt.Container) {
                org.jfree.chart.ChartPanel found = findChartPanel((java.awt.Container) comp);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Like {@link #findFieldInHierarchy(Class, String)} but returns {@code null}
     *  instead of throwing when the field is not found. Use at sites with a meaningful
     *  fallback (e.g. optional metadata like axis titles or series labels). */
    static Field findFieldInHierarchyOrNull(Class<?> clazz, String fieldName) {
        return getCachedField(clazz, fieldName).orElse(null);
    }

    static Field findFieldInHierarchy(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Optional<Field> field = getCachedField(clazz, fieldName);
        if (field.isPresent()) return field.get();
        throw new NoSuchFieldException(fieldName + " not found in class hierarchy of " + clazz.getName());
    }

    private static Optional<Field> getCachedField(Class<?> clazz, String fieldName) {
        return FIELD_CACHE.computeIfAbsent(new FieldKey(clazz, fieldName), key -> {
            Class<?> c = key.clazz();
            while (c != null) {
                try {
                    Field field = c.getDeclaredField(key.fieldName());
                    field.setAccessible(true);
                    return Optional.of(field);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            return Optional.empty();
        });
    }

    /** Returns {@code true} if every series in the dataset has the same item count
     *  (i.e. the series advance in "lockstep"), or if there are fewer than two series.
     *
     *  Incremental chart updates send only the points beyond a single per-chart cursor
     *  and append them client-side with {@code Plotly.extendTraces}. That is only correct
     *  when all series share one length; if series lengths differ, a shorter series would
     *  be over-sliced and silently lose points. Processors therefore use this check to
     *  fall back to a full (non-incremental) redraw when series are not lockstep. Do not
     *  remove this guard to "optimise" the common case — it exists for correctness. */
    static boolean seriesAllSameLength(XYSeriesCollection dataset) {
        int count = dataset.getSeriesCount();
        if (count < 2) return true;
        int first = dataset.getSeries(0).getItemCount();
        for (int i = 1; i < count; i++) {
            if (dataset.getSeries(i).getItemCount() != first) return false;
        }
        return true;
    }
}
