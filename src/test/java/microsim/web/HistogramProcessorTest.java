package microsim.web;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import microsim.engine.SimulationEngine;
import microsim.FilteredCollection;
import microsim.IfChanged;
import microsim.caching.OnceUntil;
import microsim.dev.statistics.WeightedCrossSection;
import microsim.dev.statistics.WeightedValues;
import microsim.gui.plot.Weighted_HistogramSimulationPlotter;
import org.jfree.data.statistics.HistogramType;
import org.junit.jupiter.api.Test;

class HistogramProcessorTest {
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> series(Weighted_HistogramSimulationPlotter plot) throws Exception {
        return (List<Map<String, Object>>) new ChartProcessors.HistogramProcessor().process(plot, 0).get("series");
    }

    @Test
    void beforeFirstUpdateDoesNotEvaluateSupplier() throws Exception {
        var plot = new Weighted_HistogramSimulationPlotter("Income", "Value", HistogramType.FREQUENCY, 2);
        plot.addSource("Not ready", () -> { throw new AssertionError("Web must not evaluate supplier"); });
        assertTrue(series(plot).isEmpty());
        plot.dispose();
    }

    @Test
    void preservesDesktopBoundariesLabelsAndWeights() throws Exception {
        var plot = new Weighted_HistogramSimulationPlotter("Income", "Value", HistogramType.FREQUENCY, 2, 0.0, 4.0, true);
        plot.addSource("People", () -> new WeightedValues<>(List.of(1, 2, 3), List.of(0.5, 2.0, 3.0)));
        plot.update();
        var result = series(plot).getFirst();
        assertEquals("People", result.get("name"));
        assertEquals(List.of(0.0, 2.0, 4.0), result.get("binEdges"));
        assertEquals(List.of(0.5, 5.0), result.get("counts"));
        plot.dispose();
    }

    @Test
    void webReadUsesLastScheduledSampleUntilNextUpdate() throws Exception {
        var plot = new Weighted_HistogramSimulationPlotter("Income", "Value", HistogramType.FREQUENCY, 2);
        var calls = new AtomicInteger();
        plot.addSource("Changing", () -> new WeightedValues<>(List.of(1, 3), List.of((double) calls.incrementAndGet(), 2.0)));
        plot.update();
        assertEquals(List.of(1.0, 2.0), series(plot).getFirst().get("counts"));
        series(plot);
        assertEquals(1, calls.get());
        plot.update();
        assertEquals(List.of(2.0, 2.0), series(plot).getFirst().get("counts"));
        assertEquals(2, calls.get());
        plot.dispose();
    }

    @Test
    void pollingDuringUnsetIncomeDoesNotPoisonSharedCache() throws Exception {
        var tick = new AtomicInteger();
        double[] income = {100};
        var filter = new FilteredCollection<Integer>(() -> List.of(1, 2), i -> income[0] >= 0);
        var cached = new OnceUntil<>(filter, new IfChanged<>(tick::get));
        var source = new WeightedCrossSection<>(cached, i -> income[0] + i, i -> 1.0);
        var plot = new Weighted_HistogramSimulationPlotter("Income", "Value", HistogramType.FREQUENCY, 2);
        plot.addSource("Income", source);
        plot.update();
        var previous = series(plot);
        tick.set(1);
        income[0] = -9999.99;
        assertEquals(previous, series(plot));
        income[0] = 200;
        assertDoesNotThrow(() -> plot.update());
        assertEquals(2, cached.get().size());
        assertEquals(List.of(201.0, 201.5, 202.0), series(plot).getFirst().get("binEdges"));
        plot.dispose();
    }
    @Test
    void readsCompletedSnapshotWhileEngineUpdateIsBlocked() throws Exception {
        var plot = new Weighted_HistogramSimulationPlotter("Income", "Value", HistogramType.FREQUENCY, 2);
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        plot.addSource("People", () -> {
            int call = calls.incrementAndGet();
            if (call == 2) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return new WeightedValues<>(List.of(1, 3), List.of((double) call, 2.0));
        });
        plot.update();
        var previous = plot.getCompletedHistogram();
        assertThrows(UnsupportedOperationException.class, () -> previous.clear());
        assertThrows(UnsupportedOperationException.class, () -> previous.getFirst().values().clear());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> {
                synchronized (SimulationEngine.getInstance()) { plot.update(); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var read = executor.submit(() -> series(plot));
                assertEquals(List.of(1.0, 2.0), read.get(2, TimeUnit.SECONDS).getFirst().get("counts"));
            } finally {
                release.countDown();
            }
            update.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(2.0, 2.0), series(plot).getFirst().get("counts"));
            assertEquals(List.of(1.0, 2.0), previous.getFirst().values());
        } finally {
            plot.dispose();
        }
    }

    @Test
    void failedUpdatePreservesLastCompletedSnapshotAndPropagatesError() throws Exception {
        var plot = new Weighted_HistogramSimulationPlotter("Income", "Value", HistogramType.FREQUENCY, 2);
        var calls = new AtomicInteger();
        plot.addSource("People", () -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("Bad model data");
            return new WeightedValues<>(List.of(1, 3), List.of(1.0, 2.0));
        });
        plot.update();
        var previous = series(plot);
        assertThrows(IllegalStateException.class, () -> plot.update());
        assertEquals(previous, series(plot));
        plot.dispose();
    }

}
