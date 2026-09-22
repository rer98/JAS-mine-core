/* (C) Copyright 2026, by Ross Richardson
 * Restricted-AI diagnostics from reviewed lifecycle events, never model output.
 * @author ross richardson
 */
package microsim.web.server;

import java.time.Instant;
import java.util.*;

public final class SafeDiagnostics {
    private static final ArrayDeque<Map<String,Object>> EVENTS = new ArrayDeque<>();
    private static final Set<String> ACTIONS = Set.of("build", "start", "pause", "step", "reset", "update-params",
            "startup/confirm", "startup/review", "db/query", "input/upload", "startup/upload", "storage/delete");
    private SafeDiagnostics() {}
    public static synchronized void request(String path, int status) {
        String action = path.startsWith("/simulation/") ? path.substring(12) : "";
        if (!ACTIONS.contains(action)) return;
        EVENTS.addLast(Map.of("time", Instant.now().toString(), "action", action,
                "httpStatus", status, "result", status < 400 ? "accepted" : "rejected"));
        while (EVENTS.size() > 1000) EVENTS.removeFirst();
    }
    public static synchronized void incident(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) return;
        EVENTS.addLast(Map.of("time", Instant.now().toString(), "action", "internal_error", "incidentId", id));
        while (EVENTS.size() > 1000) EVENTS.removeFirst();
    }
    public static synchronized Map<String,Object> snapshot() {
        return Map.of("events", List.copyOf(EVENTS), "restricted", true,
                "note", "Reviewed server events only. Model console output and exception text are excluded. An accepted asynchronous request is not evidence of completion.");
    }
}
