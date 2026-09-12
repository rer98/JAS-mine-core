package microsim.web.server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Coordinates bounded, idempotent cleanup when a JAS-mine Web server stops.
 * Background work is stopped once; simulation disposal waits only for a bounded
 * period and may be retried by a later lifecycle callback if the first lock
 * attempt times out.
 *
 * @author ross richardson
 */

/** Bounded shutdown coordinator for background services and simulation state. */
public final class GracefulShutdown {
    private final Lock lifecycleLock;
    private final long lockTimeoutMillis;
    private final Runnable stopBackgroundWork;
    private final Runnable disposeSimulation;
    private final Consumer<String> log;
    private final AtomicBoolean backgroundStopStarted = new AtomicBoolean(false);
    private final AtomicBoolean cleanupInProgress = new AtomicBoolean(false);
    private final AtomicBoolean cleanupFinished = new AtomicBoolean(false);

    public GracefulShutdown(
            Lock lifecycleLock,
            long lockTimeoutMillis,
            Runnable stopBackgroundWork,
            Runnable disposeSimulation,
            Consumer<String> log) {
        if (lifecycleLock == null) throw new IllegalArgumentException("lifecycleLock is required");
        if (lockTimeoutMillis < 0) throw new IllegalArgumentException("lockTimeoutMillis must be non-negative");
        if (stopBackgroundWork == null) throw new IllegalArgumentException("stopBackgroundWork is required");
        if (disposeSimulation == null) throw new IllegalArgumentException("disposeSimulation is required");
        if (log == null) throw new IllegalArgumentException("log is required");
        this.lifecycleLock = lifecycleLock;
        this.lockTimeoutMillis = lockTimeoutMillis;
        this.stopBackgroundWork = stopBackgroundWork;
        this.disposeSimulation = disposeSimulation;
        this.log = log;
    }

    public void shutdown() {
        if (cleanupFinished.get() || !cleanupInProgress.compareAndSet(false, true)) return;

        boolean locked = false;
        try {
            stopBackgroundWorkOnce();
            try {
                locked = lifecycleLock.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                safeLog("Graceful shutdown interrupted while waiting for simulation operations");
                return;
            }

            if (!locked) {
                safeLog("Graceful shutdown timed out after " + lockTimeoutMillis
                    + " ms waiting for simulation operations; cleanup will be retried after the server stops");
                return;
            }

            try {
                disposeSimulation.run();
                safeLog("Graceful shutdown disposed simulation state");
            } catch (RuntimeException e) {
                safeLog("Graceful shutdown could not fully dispose simulation state: " + errorMessage(e));
            } finally {
                cleanupFinished.set(true);
            }
        } finally {
            if (locked) lifecycleLock.unlock();
            cleanupInProgress.set(false);
        }
    }

    private void stopBackgroundWorkOnce() {
        if (!backgroundStopStarted.compareAndSet(false, true)) return;
        try {
            stopBackgroundWork.run();
        } catch (RuntimeException e) {
            safeLog("Graceful shutdown could not stop background services: " + errorMessage(e));
        }
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (RuntimeException ignored) {
            // Shutdown must continue even if diagnostic logging fails.
        }
    }

    private static String errorMessage(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
