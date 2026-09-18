package microsim.web;

import static microsim.web.ChartProcessorSupport.*;

import microsim.gui.plot.TimeSeriesSimulationPlotter;
import microsim.gui.plot.IndividualBarSimulationPlotter;
import microsim.gui.plot.Weighted_HistogramSimulationPlotter;
import microsim.gui.plot.ScatterplotSimulationPlotterRefreshable;
import microsim.gui.space.LayeredSurfaceFrame;
import microsim.gui.plot.Weighted_PyramidPlotter;

import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.data.xy.XYSeries;

import javax.swing.JInternalFrame;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;
import microsim.dev.statistics.WeightedValues;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Chart and visualisation adapters for JAS-mine Web.
 *
 * Defines the ChartProcessor interface and processor registry used to convert
 * selected microsim.gui plot and space visualisation frames into JSON-serializable
 * structures for browser rendering. Shared reflection and chart-support helpers
 * live in ChartProcessorSupport, while ChartResponseUtils handles endpoint response
 * shaping and unsupported-frame diagnostics.
 *
 * @author ross richardson
 *
 */


public class ChartProcessors {

    public interface ChartProcessor {
        boolean canProcess(JInternalFrame frame);
        Map<String, Object> process(JInternalFrame frame, int sinceIndex) throws Exception;

    // Supported GUI visualisation frames currently include time series, individual
    // bar, weighted histogram, refreshable scatter, weighted pyramid, and layered
    // surface frames. Known plot classes not yet processed include
    // CollectionBarSimulationPlotter, HistogramSimulationPlotter, and the
    // non-refreshable ScatterplotSimulationPlotter; ChartResponseUtils logs a
    // once-per-class/title diagnostic if a future model registers an unsupported frame.
    }

    private static final List<ChartProcessor> PROCESSORS = Arrays.asList(
        new TimeSeriesProcessor(),
        new BarProcessor(),
        new HistogramProcessor(),
        new ScatterProcessor(),
        new PyramidProcessor(), 
        new LayeredSurfaceProcessor()
    );

    public static List<ChartProcessor> getProcessors() {
        return PROCESSORS;
    }



    // ===== Processor Implementations =====

    static class TimeSeriesProcessor implements ChartProcessor {
        @Override
        public boolean canProcess(JInternalFrame frame) {
            return frame instanceof TimeSeriesSimulationPlotter;
        }

        @Override
        public Map<String, Object> process(JInternalFrame frame, int sinceIndex) throws Exception {
            TimeSeriesSimulationPlotter plotter = (TimeSeriesSimulationPlotter) frame;
            Map<String, Object> chartInfo = createChartInfo(plotter.getTitle(), "timeseries");
            
            Field datasetField = findFieldInHierarchy(plotter.getClass(), "dataset");
            datasetField.setAccessible(true);
            XYSeriesCollection dataset = (XYSeriesCollection) datasetField.get(plotter);

            // Only update incrementally when all series share one length; otherwise fall
            // back to a full redraw so shorter series don't silently lose points.
            boolean isIncremental = seriesAllSameLength(dataset);
            chartInfo.put("isIncremental", isIncremental);
            
            // Get axis titles from plot field (optional metadata: empty string if missing)
            String yAxisTitle = "";
            String xAxisTitle = "";
            Field plotField = findFieldInHierarchyOrNull(plotter.getClass(), "plot");
            if (plotField != null) {
                plotField.setAccessible(true);
                org.jfree.chart.plot.XYPlot plot = (org.jfree.chart.plot.XYPlot) plotField.get(plotter);
                if (plot != null) {
                    if (plot.getRangeAxis() != null) {
                        yAxisTitle = plot.getRangeAxis().getLabel();
                    }
                    if (plot.getDomainAxis() != null) {
                        xAxisTitle = plot.getDomainAxis().getLabel();
                    }
                }
            }
            chartInfo.put("yAxisTitle", yAxisTitle);
            chartInfo.put("xAxisTitle", xAxisTitle);
            
            List<Map<String, Object>> seriesList = new ArrayList<>();
            int maxItemCount = 0;
            for (int i = 0; i < dataset.getSeriesCount(); i++) {
                XYSeries series = dataset.getSeries(i);
                Map<String, Object> seriesInfo = new HashMap<>();
                seriesInfo.put("name", series.getKey().toString());
                
                int itemCount = series.getItemCount();
                if (itemCount > maxItemCount) maxItemCount = itemCount;
                int startIdx = isIncremental ? Math.max(0, Math.min(sinceIndex, itemCount)) : 0;

                List<Double> xData = new ArrayList<>();
                List<Double> yData = new ArrayList<>();
                for (int j = startIdx; j < itemCount; j++) {
                    xData.add(series.getX(j).doubleValue());
                    yData.add(series.getY(j).doubleValue());
                }
                seriesInfo.put("x", xData);
                seriesInfo.put("y", yData);
                seriesList.add(seriesInfo);
            }
            chartInfo.put("nextIndex", maxItemCount);
            
            finalizeChartInfo(chartInfo, plotter, seriesList);
            return chartInfo;
        }
    }


    static class BarProcessor implements ChartProcessor {
        @Override
        public boolean canProcess(JInternalFrame frame) {
            return frame instanceof IndividualBarSimulationPlotter;
        }

        @Override
        public Map<String, Object> process(JInternalFrame frame, int sinceIndex) throws Exception {
            IndividualBarSimulationPlotter plotter = (IndividualBarSimulationPlotter) frame;
            Map<String, Object> chartInfo = createChartInfo(plotter.getTitle(), "bar");
            chartInfo.put("isIncremental", false);
            
            Field datasetField = findFieldInHierarchy(plotter.getClass(), "dataset");
            datasetField.setAccessible(true);
            org.jfree.data.category.DefaultCategoryDataset dataset = 
                (org.jfree.data.category.DefaultCategoryDataset) datasetField.get(plotter);
            
            String yAxisTitle = "";
            Field yaxisField = findFieldInHierarchyOrNull(plotter.getClass(), "yaxis");
            if (yaxisField != null) {
                yaxisField.setAccessible(true);
                yAxisTitle = (String) yaxisField.get(plotter);
            }
            chartInfo.put("yAxisTitle", yAxisTitle);

            String xAxisTitle = "";
            try {
                org.jfree.chart.ChartPanel chartPanel = (org.jfree.chart.ChartPanel) plotter.getContentPane();
                org.jfree.chart.JFreeChart chart = chartPanel.getChart();
                org.jfree.chart.plot.CategoryPlot plot = chart.getCategoryPlot();
                if (plot != null && plot.getDomainAxis() != null) {
                    xAxisTitle = plot.getDomainAxis().getLabel();
                }
            } catch (Exception e) {
                // Fall back to empty string
            }
            chartInfo.put("xAxisTitle", xAxisTitle);

            List<String> labels = new ArrayList<>();
            for (int c = 0; c < dataset.getColumnCount(); c++) {
                labels.add(dataset.getColumnKey(c).toString());
            }
            chartInfo.put("labels", labels);
            
            List<Map<String, Object>> seriesList = new ArrayList<>();
            for (int r = 0; r < dataset.getRowCount(); r++) {
                Comparable rowKey = dataset.getRowKey(r);
                Map<String, Object> seriesInfo = new HashMap<>();
                seriesInfo.put("name", rowKey.toString());
                
                List<Double> values = new ArrayList<>();
                for (int c = 0; c < dataset.getColumnCount(); c++) {
                    Comparable cat = dataset.getColumnKey(c);
                    Number val = dataset.getValue(rowKey, cat);
                    values.add(val != null ? val.doubleValue() : 0.0);
                }
                seriesInfo.put("values", values);
                seriesList.add(seriesInfo);
            }
            
            finalizeChartInfo(chartInfo, plotter, seriesList);
            return chartInfo;
        }
    }

    static class HistogramProcessor implements ChartProcessor {

        @Override
        public boolean canProcess(JInternalFrame frame) {
            return frame instanceof Weighted_HistogramSimulationPlotter;
        }

        @Override
        public Map<String, Object> process(JInternalFrame frame, int sinceIndex) throws Exception {
            Map<String, Object> chartInfo = createChartInfo(frame.getTitle(), "histogram");
            chartInfo.put("isIncremental", false);
            
            Field binsField = findFieldInHierarchy(frame.getClass(), "bins");
            binsField.setAccessible(true);
            int bins = (int) binsField.get(frame);
            
            Field sourcesField = findFieldInHierarchy(frame.getClass(), "sources");
            sourcesField.setAccessible(true);
            ArrayList<?> sources = (ArrayList<?>) sourcesField.get(frame);

            Field labelsField = findFieldInHierarchy(frame.getClass(), "labels");
            labelsField.setAccessible(true);
            List<?> labels = (List<?>) labelsField.get(frame);

            List<Map<String, Object>> seriesList = new ArrayList<>();
            for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
                Object source = sources.get(sourceIndex);
                Map<String, Object> seriesInfo = new HashMap<>();
                seriesInfo.put("name", labels.get(sourceIndex));

                try {
                    // Use the same supplier contract as the desktop plotter.
                    // Read once so values and weights describe the same sample.
                    WeightedValues<?> sample = (WeightedValues<?>) ((Supplier<?>) source).get();
                    double[] values = sample.values().stream()
                        .mapToDouble(value -> ((Number) value).doubleValue()).toArray();
                    double[] weights = sample.weights().stream().mapToDouble(Double::doubleValue).toArray();
                    if (values.length == 0) continue;
                    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (double v : values) {
                        if (v < min) min = v;
                        if (v > max) max = v;
                    }
                    if (min == max) {
                        double pad = Math.max(Math.abs(min) * 0.01, 0.5);
                        min -= pad;
                        max += pad;
                    }
                    
                    double binWidth = (max - min) / bins;
                    double[] binCounts = new double[bins];
                    List<Double> binEdges = new ArrayList<>();
                    
                    for (int i = 0; i <= bins; i++) {
                        binEdges.add(min + i * binWidth);
                    }
                    
                    for (int i = 0; i < values.length; i++) {
                        int binIndex = (int) ((values[i] - min) / binWidth);
                        if (binIndex >= bins) binIndex = bins - 1;
                        if (binIndex < 0) binIndex = 0;
                        binCounts[binIndex] += weights[i];
                    }
                    
                    List<Double> counts = new ArrayList<>();
                    for (double c : binCounts) counts.add(c);
                    
                    seriesInfo.put("binEdges", binEdges);
                    seriesInfo.put("counts", counts);
                } catch (Exception e) {
                    SimulationServer.addLogMessage("HistogramProcessor: error processing series '"
                        + seriesInfo.get("name") + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    seriesInfo.put("binEdges", new ArrayList<>());
                    seriesInfo.put("counts", new ArrayList<>());
                }
                seriesList.add(seriesInfo);
            }
            
            String xAxisTitle = "";
            String yAxisTitle = "";
            try {
                org.jfree.chart.ChartPanel chartPanel = findChartPanel(frame.getContentPane());
                org.jfree.chart.JFreeChart chart = chartPanel != null ? chartPanel.getChart() : null;
                org.jfree.chart.plot.XYPlot plot = chart != null ? chart.getXYPlot() : null;
                if (plot != null) {
                    if (plot.getDomainAxis() != null) {
                        xAxisTitle = plot.getDomainAxis().getLabel();
                    }
                    if (plot.getRangeAxis() != null) {
                        yAxisTitle = plot.getRangeAxis().getLabel();
                    }
                }
            } catch (Exception e) {
                // Fall back to empty strings
            }
            chartInfo.put("xAxisTitle", xAxisTitle);
            chartInfo.put("yAxisTitle", yAxisTitle);

            finalizeChartInfo(chartInfo, frame, seriesList);
            return chartInfo;
        }
    }

    static class ScatterProcessor implements ChartProcessor {

        @Override
        public boolean canProcess(JInternalFrame frame) {
            return frame instanceof ScatterplotSimulationPlotterRefreshable;
        }

        @Override
        public Map<String, Object> process(JInternalFrame frame, int sinceIndex) throws Exception {
            ScatterplotSimulationPlotterRefreshable plotter = (ScatterplotSimulationPlotterRefreshable) frame;
            
            Map<String, Object> chartInfo = createChartInfo(frame.getTitle(), "scatter");
            
            Field datasetField = findFieldInHierarchy(frame.getClass(), "dataset");
            datasetField.setAccessible(true);
            XYSeriesCollection dataset = (XYSeriesCollection) datasetField.get(frame);

            // Incremental only in trajectory mode (getMaxSamples() == 0, accumulate all)
            // and only when all series share one length; otherwise fall back to a full
            // redraw so shorter series don't silently lose points.
            boolean isIncremental = (plotter.getMaxSamples() == 0) && seriesAllSameLength(dataset);
            chartInfo.put("isIncremental", isIncremental);
            
            // Get axis titles via ChartPanel
            String yAxisTitle = "";
            String xAxisTitle = "";
            try {
                org.jfree.chart.ChartPanel chartPanel = findChartPanel(frame.getContentPane());
                org.jfree.chart.JFreeChart chart = chartPanel != null ? chartPanel.getChart() : null;
                org.jfree.chart.plot.XYPlot plot = chart != null ? chart.getXYPlot() : null;
                if (plot != null) {
                    if (plot.getRangeAxis() != null) {
                        yAxisTitle = plot.getRangeAxis().getLabel();
                    }
                    if (plot.getDomainAxis() != null) {
                        xAxisTitle = plot.getDomainAxis().getLabel();
                    }
                }
            } catch (Exception e) {
                // Fall back to empty strings
            }
            chartInfo.put("yAxisTitle", yAxisTitle);
            chartInfo.put("xAxisTitle", xAxisTitle);
            
            List<Map<String, Object>> seriesList = new ArrayList<>();
            int totalMaxIndex = 0;
            
            for (int i = 0; i < dataset.getSeriesCount(); i++) {
                XYSeries series = dataset.getSeries(i);
                Map<String, Object> seriesInfo = new HashMap<>();
                seriesInfo.put("name", series.getKey().toString());
                
                List<Double> xData = new ArrayList<>();
                List<Double> yData = new ArrayList<>();
                
                int startIdx = isIncremental ? sinceIndex : 0;
                int itemCount = series.getItemCount();
                totalMaxIndex = Math.max(totalMaxIndex, itemCount);
                
                for (int j = startIdx; j < itemCount; j++) {
                    xData.add(series.getX(j).doubleValue());
                    yData.add(series.getY(j).doubleValue());
                }
                seriesInfo.put("x", xData);
                seriesInfo.put("y", yData);
                seriesList.add(seriesInfo);
            }
            
            if (isIncremental) {
                chartInfo.put("nextIndex", totalMaxIndex);
            }
            
            finalizeChartInfo(chartInfo, frame, seriesList);
            return chartInfo;
        }
    }

    static class PyramidProcessor implements ChartProcessor {
        @Override
        public boolean canProcess(JInternalFrame frame) {
            return frame instanceof microsim.gui.plot.Weighted_PyramidPlotter;
        }

        @Override
        public Map<String, Object> process(JInternalFrame frame, int sinceIndex) throws Exception {
            Map<String, Object> chartInfo = createChartInfo(frame.getTitle(), "pyramid");
            chartInfo.put("isIncremental", false);

            // Get the chart field to access the CategoryPlot and dataset
            Field chartField = findFieldInHierarchy(frame.getClass(), "chart");
            chartField.setAccessible(true);
            org.jfree.chart.JFreeChart chart = (org.jfree.chart.JFreeChart) chartField.get(frame);

            // Get axis titles from the chart's CategoryPlot
            // Note: pyramid uses HORIZONTAL orientation, so domain axis = groups (vertical),
            // range axis = values (horizontal). The range axis is hidden in the desktop GUI.
            String xAxisTitle = "";
            String yAxisTitle = "";
            if (chart != null) {
                org.jfree.chart.plot.CategoryPlot plot = chart.getCategoryPlot();
                if (plot != null) {
                    if (plot.getDomainAxis() != null && plot.getDomainAxis().getLabel() != null) {
                        xAxisTitle = plot.getDomainAxis().getLabel();
                    }
                    if (plot.getRangeAxis() != null && plot.getRangeAxis().getLabel() != null) {
                        yAxisTitle = plot.getRangeAxis().getLabel();
                    }
                }
            }
            chartInfo.put("xAxisTitle", xAxisTitle);
            chartInfo.put("yAxisTitle", yAxisTitle);

            // Try to get full group labels from groupNames field (toString() hides some for readability)
            List<String> labels = new ArrayList<>();
            Field groupNamesField = findFieldInHierarchyOrNull(frame.getClass(), "groupNames");
            if (groupNamesField != null) {
                try {
                    groupNamesField.setAccessible(true);
                    Object[] groupNameObjs = (Object[]) groupNamesField.get(frame);
                    if (groupNameObjs != null) {
                        for (Object gn : groupNameObjs) {
                            Field valueField = findFieldInHierarchy(gn.getClass(), "value");
                            valueField.setAccessible(true);
                            labels.add((String) valueField.get(gn));
                        }
                    }
                } catch (Exception e) {
                    // Fall back to chart dataset column keys below
                }
            }

            // Extract series data from the chart's CategoryDataset
            List<Map<String, Object>> seriesList = new ArrayList<>();
            if (chart != null) {
                org.jfree.chart.plot.CategoryPlot plot = chart.getCategoryPlot();
                org.jfree.data.category.CategoryDataset dataset = plot.getDataset();

                if (dataset != null) {
                    // Fall back to dataset column keys if groupNames extraction failed
                    if (labels.isEmpty()) {
                        for (int c = 0; c < dataset.getColumnCount(); c++) {
                            labels.add(dataset.getColumnKey(c).toString());
                        }
                    }

                    // Extract series (typically two: left and right, e.g. Males and Females)
                    // Left series values are already negative in the dataset
                    for (int r = 0; r < dataset.getRowCount(); r++) {
                        Map<String, Object> seriesInfo = new HashMap<>();
                        seriesInfo.put("name", dataset.getRowKey(r).toString());

                        List<Double> values = new ArrayList<>();
                        for (int c = 0; c < dataset.getColumnCount(); c++) {
                            Number val = dataset.getValue(r, c);
                            values.add(val != null ? val.doubleValue() : 0.0);
                        }
                        seriesInfo.put("values", values);
                        seriesList.add(seriesInfo);
                    }
                }
            }

            chartInfo.put("labels", labels);
            finalizeChartInfo(chartInfo, frame, seriesList);
            return chartInfo;
        }
    }


    static class LayeredSurfaceProcessor implements ChartProcessor {
        @Override
        public boolean canProcess(JInternalFrame frame) {
            return frame instanceof LayeredSurfaceFrame;
        }

        @Override
        public Map<String, Object> process(JInternalFrame frame, int sinceIndex) throws Exception {
            LayeredSurfaceFrame surface = (LayeredSurfaceFrame) frame;
            Map<String, Object> chartInfo = createChartInfo(surface.getTitle(), "grid");
            chartInfo.put("isIncremental", false);
            
            // Access the panel
            Field panelField = findFieldInHierarchy(surface.getClass(), "jLayeredPanel");
            panelField.setAccessible(true);
            Object panel = panelField.get(surface);
            
            // Get layers
            Method getLayersMethod = getCachedMethod(panel.getClass(), "getLayers");
            List<?> layers = (List<?>) getLayersMethod.invoke(panel);
            
            if (layers.isEmpty()) {
                chartInfo.put("series", new ArrayList<>());
                return chartInfo;
            }
            
            // Get grid size from first layer
            Object firstLayer = layers.get(0);
            Field spaceField = findFieldInHierarchy(firstLayer.getClass(), "space");
            spaceField.setAccessible(true);
            Object firstSpace = spaceField.get(firstLayer);
            
            Method getXSize = getCachedMethod(firstSpace.getClass(), "getXSize");
            Method getYSize = getCachedMethod(firstSpace.getClass(), "getYSize");
            int xSize = (int) getXSize.invoke(firstSpace);
            int ySize = (int) getYSize.invoke(firstSpace);
            
            chartInfo.put("xSize", xSize);
            chartInfo.put("ySize", ySize);
            
            // Build color mapping from all layers and track final colors
            Map<Integer, String> colorMapping = new HashMap<>();
            int[][] finalColors = new int[xSize][ySize];
            for (int[] row : finalColors) Arrays.fill(row, GRID_EMPTY_COLOR_VALUE);
            
            // Process each layer (bottom to top, so later layers overwrite)
            for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
                Object layer = layers.get(layerIndex);
                try {
                    Field layerSpaceField = findFieldInHierarchy(layer.getClass(), "space");
                    layerSpaceField.setAccessible(true);
                    Object space = layerSpaceField.get(layer);
                    
                    Method getMethod = getCachedMethod(space.getClass(), "get", int.class, int.class);
                    
                    // Get color map and invoker for this layer (both optional)
                    Field colorMapField = findFieldInHierarchyOrNull(layer.getClass(), "colorMap");
                    Object colorMap = null;
                    if (colorMapField != null) {
                        colorMapField.setAccessible(true);
                        colorMap = colorMapField.get(layer);
                    }
                    
                    Field invokerField = findFieldInHierarchyOrNull(layer.getClass(), "invoker");
                    Object invoker = null;
                    if (invokerField != null) {
                        invokerField.setAccessible(true);
                        invoker = invokerField.get(layer);
                    }

                    Method invokerGetInt = null;
                    Method invokerGetDouble = null;
                    if (invoker != null) {
                        String invokerName = invoker.getClass().getSimpleName();
                        if ("IntegerInvoker".equals(invokerName)) invokerGetInt = getCachedMethod(invoker.getClass(), "getInt", Object.class);
                        else if ("DoubleInvoker".equals(invokerName)) invokerGetDouble = getCachedMethod(invoker.getClass(), "getDouble", Object.class);
                    }

                    // Extract colors from this layer's colorMap
                    if (colorMap != null) {
                        Field componentsField = findFieldInHierarchy(colorMap.getClass(), "colorComponents");
                        componentsField.setAccessible(true);
                        int[][] colorComponents = (int[][]) componentsField.get(colorMap);
                        
                        Field mapperField = findFieldInHierarchy(colorMap.getClass(), "mapper");
                        mapperField.setAccessible(true);
                        Map<Integer, Integer> mapper = (Map<Integer, Integer>) mapperField.get(colorMap);
                        
                        for (Map.Entry<Integer, Integer> entry : mapper.entrySet()) {
                            int value = entry.getKey();
                            int index = entry.getValue();
                            int[] rgb = colorComponents[index];
                            String hexColor = String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
                            colorMapping.put(value, hexColor);
                        }
                    }

                    // Check for direct color (when no colorMap)
                    Color directColor = null;
                    Field colorField = findFieldInHierarchyOrNull(layer.getClass(), "c");
                    if (colorField != null) {
                        colorField.setAccessible(true);
                        directColor = (Color) colorField.get(layer);
                    }                    

                    // Add direct color to mapping if present
                    // Use negative sentinels for direct-colour cells: real ColorMap values
                    // are non-negative agent-state IDs, so negatives can't collide.
                    int directColorValue = -(layerIndex + 1);
                    if (directColor != null) {
                        String hexColor = String.format("#%02x%02x%02x", 
                            directColor.getRed(), directColor.getGreen(), directColor.getBlue());
                        colorMapping.put(directColorValue, hexColor);
                    }

                    // Scan grid for this layer and overwrite colors
                    int agentCount = 0;
                    for (int x = 0; x < xSize; x++) {
                        for (int y = 0; y < ySize; y++) {
                            Object agent = getMethod.invoke(space, x, y);
                            if (agent != null) {
                                agentCount++;
                                int colorValue = directColorValue;  // Default to direct color
                                if (invokerGetInt != null) {
                                    colorValue = (int) invokerGetInt.invoke(invoker, agent);
                                } else if (invokerGetDouble != null) {
                                    colorValue = (int) (double) invokerGetDouble.invoke(invoker, agent);
                                }
                                finalColors[x][y] = colorValue;
                            }
                        }
                    }

                } catch (Exception e) {
                    // Skip layer if it can't be processed, but surface the failure
                    SimulationServer.addLogMessage("LayeredSurfaceProcessor: error processing layer "
                        + layerIndex + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            
            chartInfo.put("colorMapping", colorMapping);
            
            // Build agents list from final colors
            List<Map<String, Object>> agents = new ArrayList<>();
            for (int x = 0; x < xSize; x++) {
                for (int y = 0; y < ySize; y++) {
                    if (finalColors[x][y] != GRID_EMPTY_COLOR_VALUE) {
                        Map<String, Object> agentData = new HashMap<>();
                        agentData.put("x", x);
                        agentData.put("y", y);
                        agentData.put("colorValue", finalColors[x][y]);
                        agents.add(agentData);
                    }
                }
            }
            chartInfo.put("agents", agents);
            chartInfo.put("series", new ArrayList<>());
            
            return chartInfo;
        }

    }


}
