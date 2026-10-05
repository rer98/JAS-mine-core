package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/* (C) Copyright 2026, by Ross Richardson
 *
 * Checks that the original public web-package memory monitor remains usable
 * while its implementation delegates to the shared SingleRun/MultiRun monitor.
 *
 * @author ross richardson
 */

@SuppressWarnings("deprecation")
class MemoryMonitorCompatibilityTest {
    @Test
    void oldPublicLifecycleAndIntervalsRemainAvailable() {
        assertEquals(30_000L, MemoryMonitor.DEFAULT_WARNING_INTERVAL_MS);
        assertEquals(10_000L, MemoryMonitor.DEFAULT_POLL_INTERVAL_MS);
        try (MemoryMonitor monitor = new MemoryMonitor(message -> { })) {
            monitor.start();
            monitor.start();
            monitor.stop();
            monitor.stop();
        }
    }
}
