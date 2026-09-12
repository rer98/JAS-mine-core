package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for SimulationLifecycleResponses.
 * Verifies the focused JAS-mine Web helper behaviour implemented by SimulationLifecycleResponses
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class SimulationLifecycleResponsesTest {
    @Test
    public void notInitializedStatusMatchesEndpointShape() {
        assertEquals(Map.of("status", "not_initialized"), SimulationLifecycleResponses.notInitializedStatus());
    }

    @Test
    public void currentStatusReportsRunningOrPausedWithTimeAndBuildFlag() {
        assertEquals(
            Map.of("status", "running", "time", 12.5, "built", true),
            SimulationLifecycleResponses.currentStatus(true, 12.5, true)
        );
        assertEquals(
            Map.of("status", "paused", "time", 0.0, "built", false),
            SimulationLifecycleResponses.currentStatus(false, 0.0, false)
        );
    }

    @Test
    public void timedAndSimpleStatusesMatchEndpointShapes() {
        assertEquals(Map.of("status", "started", "time", 3.0), SimulationLifecycleResponses.timedStatus("started", 3.0));
        assertEquals(Map.of("status", "reset"), SimulationLifecycleResponses.simpleStatus("reset"));
        assertEquals(Map.of("status", "speed set", "delay", 250), SimulationLifecycleResponses.speedSet(250));
    }

    @Test
    public void restartableEngineStateErrorMatchesEndpointShape() {
        Map<String, Object> res = SimulationLifecycleResponses.restartableEngineStateError();
        assertEquals(true, res.get("restartable"));
        assertEquals("Engine state error. Please restart the Java server and refresh the browser page.", res.get("error"));
    }
}
