package microsim.web.server;

import java.util.Map;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Shared simulation lifecycle response-shaping helpers.
 * Builds small JSON-compatible maps for not-initialized, built, started, paused, stepped,
 * reset, speed, and restartable-engine-state responses.
 *
 * @author ross richardson
 *
 */

/** Shared JSON response shapes for simulation lifecycle endpoints. */
public final class SimulationLifecycleResponses {
    private SimulationLifecycleResponses() {}

    public static Map<String, Object> notInitializedStatus() {
        return Map.of("status", "not_initialized");
    }

    public static Map<String, Object> currentStatus(boolean running, double time, boolean built) {
        return Map.of(
            "status", running ? "running" : "paused",
            "time", time,
            "built", built
        );
    }

    public static Map<String, Object> timedStatus(String status, double time) {
        return Map.of("status", status, "time", time);
    }

    public static Map<String, Object> simpleStatus(String status) {
        return Map.of("status", status);
    }

    public static Map<String, Object> speedSet(int delayMillis) {
        return Map.of("status", "speed set", "delay", delayMillis);
    }

    public static Map<String, Object> restartableEngineStateError() {
        return Map.of(
            "error", "Engine state error. Please restart the Java server and refresh the browser page.",
            "restartable", true
        );
    }
}
