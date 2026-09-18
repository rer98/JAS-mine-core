package microsim.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import microsim.dev.statistics.WeightedCrossSection;
import microsim.dev.statistics.WeightedValues;
import microsim.gui.plot.Weighted_HistogramSimulationPlotter;
import org.jfree.data.statistics.HistogramType;
import org.junit.jupiter.api.Test;

class HistogramProcessorTest {
    private Weighted_HistogramSimulationPlotter plot() {
        return new Weighted_HistogramSimulationPlotter("Income", "Value", HistogramType.FREQUENCY, 2);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> series(Weighted_HistogramSimulationPlotter plot) throws Exception {
        return (List<Map<String, Object>>) new ChartProcessors.HistogramProcessor().process(plot, 0).get("series");
    }

    @Test
    void currentCrossSectionPreservesLabelAndWeightedBinTotals() throws Exception {
        var plot = plot();
        var source = new WeightedCrossSection<Integer, Integer>(
            () -> List.of(1, 2, 3), value -> value, value -> value.doubleValue());
        plot.addSource("People", source);
        var result = series(plot).getFirst();
        assertEquals("People", result.get("name"));
        assertEquals(List.of(1.0, 2.0, 3.0), result.get("binEdges"));
        assertEquals(List.of(1.0, 5.0), result.get("counts"));
    }

    @Test
    void supplierReadOncePerResponseAndRefreshedBetweenResponses() throws Exception {
        var plot = plot();
        var calls = new AtomicInteger();
        plot.addSource("Changing", () -> new WeightedValues<>(List.of(7L, 7L),
            List.of((double) calls.incrementAndGet(), 2.0)));
        var first = series(plot).getFirst();
        assertEquals(1, calls.get());
        assertEquals(List.of(6.5, 7.0, 7.5), first.get("binEdges"));
        assertEquals(List.of(0.0, 3.0), first.get("counts"));
        assertEquals(List.of(0.0, 4.0), series(plot).getFirst().get("counts"));
        assertEquals(2, calls.get());
    }

    @Test
    void emptySourceDoesNotShiftFollowingLabels() throws Exception {
        var plot = plot();
        plot.addSource("Empty", () -> new WeightedValues<Double>(List.of(), List.of()));
        plot.addSource("Present", () -> new WeightedValues<>(List.of(1.5, 2.5), List.of(0.25, 0.75)));
        var result = series(plot);
        assertEquals(1, result.size());
        assertEquals("Present", result.getFirst().get("name"));
        assertEquals(List.of(0.25, 0.75), result.getFirst().get("counts"));
    }

    @Test
    void brokenSupplierKeepsDiagnosticSeriesAndDoesNotSuppressGoodSeries() throws Exception {
        var plot = plot();
        plot.addSource("Broken", () -> { throw new IllegalStateException("Unavailable sample"); });
        plot.addSource("Good", () -> new WeightedValues<>(List.of(1, 3), List.of(2.0, 4.0)));
        var result = series(plot);
        assertEquals("Broken", result.getFirst().get("name"));
        assertEquals(List.of(), result.getFirst().get("counts"));
        assertEquals("Good", result.get(1).get("name"));
        assertEquals(List.of(2.0, 4.0), result.get(1).get("counts"));
    }
}
