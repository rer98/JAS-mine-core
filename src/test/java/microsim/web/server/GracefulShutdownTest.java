package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for bounded, idempotent graceful-shutdown coordination.
 *
 * @author ross richardson
 */

public class GracefulShutdownTest {
    @Test
    public void stopsBackgroundAndDisposesSimulationOnlyOnce() {
        ReentrantLock lock = new ReentrantLock();
        AtomicInteger backgroundStops = new AtomicInteger();
        AtomicInteger disposals = new AtomicInteger();
        List<String> logs = new ArrayList<>();
        GracefulShutdown shutdown = new GracefulShutdown(
            lock, 100, backgroundStops::incrementAndGet, disposals::incrementAndGet, logs::add
        );

        shutdown.shutdown();
        shutdown.shutdown();

        assertEquals(1, backgroundStops.get());
        assertEquals(1, disposals.get());
        assertFalse(lock.isLocked());
        assertTrue(logs.stream().anyMatch(s -> s.contains("disposed simulation state")));
    }

    @Test
    public void retriesDisposalAfterInitialLockTimeout() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                lockHeld.countDown();
                releaseLock.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        holder.start();
        assertTrue(lockHeld.await(1, TimeUnit.SECONDS));

        AtomicInteger backgroundStops = new AtomicInteger();
        AtomicInteger disposals = new AtomicInteger();
        List<String> logs = new ArrayList<>();
        GracefulShutdown shutdown = new GracefulShutdown(
            lock, 10, backgroundStops::incrementAndGet, disposals::incrementAndGet, logs::add
        );

        shutdown.shutdown();
        assertEquals(1, backgroundStops.get());
        assertEquals(0, disposals.get());
        assertTrue(logs.stream().anyMatch(s -> s.contains("timed out")));

        releaseLock.countDown();
        holder.join(1000);
        assertFalse(holder.isAlive());

        shutdown.shutdown();
        assertEquals(1, backgroundStops.get());
        assertEquals(1, disposals.get());
        assertFalse(lock.isLocked());
    }

    @Test
    public void cleanupFailuresAreLoggedAndDoNotEscapeOrRepeat() {
        ReentrantLock lock = new ReentrantLock();
        AtomicInteger attempts = new AtomicInteger();
        List<String> logs = new ArrayList<>();
        GracefulShutdown shutdown = new GracefulShutdown(
            lock,
            100,
            () -> {},
            () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("boom");
            },
            logs::add
        );

        shutdown.shutdown();
        shutdown.shutdown();

        assertEquals(1, attempts.get());
        assertFalse(lock.isLocked());
        assertTrue(logs.stream().anyMatch(s -> s.contains("IllegalStateException: boom")));
    }
}
