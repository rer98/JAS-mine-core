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
}
