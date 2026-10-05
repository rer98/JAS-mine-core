package microsim.web.server;

import java.util.function.Consumer;

/* (C) Copyright 2026, by Ross Richardson
 *
 * Compatibility facade for the memory monitor's original web-server package.
 * Interactive and batch applications share microsim.monitoring.MemoryMonitor.
 *
 * @author ross richardson
 */

/** Retains the original public API for integrations compiled before the shared monitor. */
@Deprecated(since = "5.2.0-web", forRemoval = false)
public final class MemoryMonitor implements AutoCloseable {
    public static final long DEFAULT_WARNING_INTERVAL_MS =
        microsim.monitoring.MemoryMonitor.DEFAULT_WARNING_INTERVAL_MS;
    public static final long DEFAULT_POLL_INTERVAL_MS =
        microsim.monitoring.MemoryMonitor.DEFAULT_POLL_INTERVAL_MS;

    private final microsim.monitoring.MemoryMonitor delegate;

    public MemoryMonitor(Consumer<String> log) {
        delegate = new microsim.monitoring.MemoryMonitor(log);
    }

    public void start() {
        delegate.start();
    }

    public void stop() {
        delegate.stop();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
