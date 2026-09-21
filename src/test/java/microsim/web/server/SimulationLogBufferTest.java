package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for SimulationLogBuffer.
 * Verifies the focused JAS-mine Web helper behaviour implemented by SimulationLogBuffer
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class SimulationLogBufferTest {
    @Test
    public void tailScopesToLatestRunAndReportsOutputMarker() {
        SimulationLogBuffer logs = new SimulationLogBuffer(100);
        logs.add("old run line");
        logs.add("--- Building New Simulation");
        logs.add("simulation output");
        logs.add("--- Output run: 20260703084523 ---");

        Map<String, Object> tail = logs.tail(10);

        assertEquals(1L, tail.get("scopeStartIndex"));
        assertEquals("20260703084523", tail.get("outputRun"));
        assertEquals(3, ((List<?>) tail.get("lines")).size());
    }
    @Test
    public void pollRedactsSecretsWithoutChangingCursor() {
        SimulationLogBuffer logs = new SimulationLogBuffer(100);
        logs.add("ordinary line");
        logs.add("password=hunter2");
        logs.add("Bearer abc.def");

        Map<String, Object> poll = logs.poll(0);

        assertEquals(
            List.of("ordinary line", "password=[REDACTED]", "Bearer [REDACTED]"),
            poll.get("logs")
        );
        assertEquals(0L, poll.get("firstIndex"));
        assertEquals(3L, poll.get("nextIndex"));
    }

    @Test
    void rolloverKeepsExactCursorsForPollTailAndSearch() {
        SimulationLogBuffer logs = new SimulationLogBuffer(3);
        for (int i = 0; i < 8; i++) logs.add("line-" + i);
        assertEquals(List.of("line-5", "line-6", "line-7"), logs.poll(0).get("logs"));
        assertEquals(5L, logs.poll(0).get("firstIndex"));
        assertEquals(8L, logs.poll(0).get("nextIndex"));
        assertEquals(List.of("line-7"), logs.poll(7).get("logs"));
        assertEquals(List.of(), logs.poll(Long.MAX_VALUE).get("logs"));
        assertEquals(List.of("line-5", "line-6", "line-7"), logs.poll(Long.MIN_VALUE).get("logs"));
        var tail = logs.tail(2);
        assertEquals(6L, tail.get("startIndex"));
        assertEquals(8L, tail.get("nextIndex"));
        var search = logs.search("line-6", false, true, 10, 1);
        var match = (Map<?, ?>) ((List<?>) search.get("matches")).get(0);
        assertEquals(6L, match.get("index"));
        assertEquals(5L, match.get("contextStartIndex"));
        assertEquals(8L, search.get("nextIndex"));
    }

    @Test
    void concurrentSnapshotsKeepLineNumbersAndCursorsTogether() throws Exception {
        SimulationLogBuffer logs = new SimulationLogBuffer(32);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writer = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 4000; i++) logs.add("line-" + i);
                return null;
            });
            var reader = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    var poll = logs.poll(0);
                    var lines = (List<?>) poll.get("logs");
                    long first = (Long) poll.get("firstIndex");
                    assertTrue(lines.size() <= 32);
                    assertEquals(first + lines.size(), poll.get("nextIndex"));
                    for (int j = 0; j < lines.size(); j++) assertEquals("line-" + (first + j), lines.get(j));
                    var tail = logs.tail(5);
                    var tailLines = (List<?>) tail.get("lines");
                    long tailStart = (Long) tail.get("startIndex");
                    for (int j = 0; j < tailLines.size(); j++) assertEquals("line-" + (tailStart + j), tailLines.get(j));
                    var search = logs.search("line-", false, true, 3, 0);
                    for (Object item : (List<?>) search.get("matches")) {
                        var match = (Map<?, ?>) item;
                        assertEquals("line-" + match.get("index"), match.get("line"));
                    }
                }
                return null;
            });
            start.countDown();
            writer.get(30, java.util.concurrent.TimeUnit.SECONDS);
            reader.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(4000L, logs.poll(0).get("nextIndex"));
    }

    @Test
    void multipleWritersRespectCapacityAndDoNotLoseCursorIncrements() throws Exception {
        SimulationLogBuffer logs = new SimulationLogBuffer(17);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                int id = worker;
                jobs.add(workers.submit(() -> {
                    for (int i = 0; i < 1000; i++) logs.add(id + ":" + i);
                }));
            }
            for (var job : jobs) job.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        var poll = logs.poll(0);
        assertEquals(3983L, poll.get("firstIndex"));
        assertEquals(4000L, poll.get("nextIndex"));
        assertEquals(17, ((List<?>) poll.get("logs")).size());
    }

}
