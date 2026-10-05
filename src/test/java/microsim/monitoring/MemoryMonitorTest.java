package microsim.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for MemoryMonitor.
 * Verifies the shared cgroup and heap monitoring used by SingleRun and MultiRun.
 *
 * @author ross richardson
 *
 */

public class MemoryMonitorTest {
    @TempDir
    Path root;

    private MemoryMonitor monitor(java.util.function.Consumer<String> log, long heapUsed) {
        return new MemoryMonitor(log, () -> new MemoryUsage(0, heapUsed, 1000, 1000),
            root, root.resolve("v1"), 60_000L, 10L);
    }

    private void container(long limit, long used, long inactive) throws Exception {
        Files.writeString(root.resolve("memory.max"), Long.toString(limit));
        Files.writeString(root.resolve("memory.current"), Long.toString(used));
        Files.writeString(root.resolve("memory.stat"), "inactive_file " + inactive + "\n");
    }

    @Test
    public void readContainerMemoryLimitPrefersFiniteCgroupV2Value() throws Exception {
        Path dir = Files.createTempDirectory("memory-monitor-limit-v2");
        Path v2 = dir.resolve("memory.max");
        Path v1 = dir.resolve("memory.limit_in_bytes");
        Files.writeString(v2, "12345\n", StandardCharsets.UTF_8);
        Files.writeString(v1, "67890\n", StandardCharsets.UTF_8);

        OptionalLong limit = MemoryMonitor.readContainerMemoryLimit(v2, v1);

        assertTrue(limit.isPresent());
        assertEquals(12345L, limit.getAsLong());
    }

    @Test
    public void readContainerMemoryLimitFallsBackFromCgroupV2MaxToV1() throws Exception {
        Path dir = Files.createTempDirectory("memory-monitor-limit-v1");
        Path v2 = dir.resolve("memory.max");
        Path v1 = dir.resolve("memory.limit_in_bytes");
        Files.writeString(v2, "max\n", StandardCharsets.UTF_8);
        Files.writeString(v1, "67890\n", StandardCharsets.UTF_8);

        OptionalLong limit = MemoryMonitor.readContainerMemoryLimit(v2, v1);

        assertTrue(limit.isPresent());
        assertEquals(67890L, limit.getAsLong());
    }

    @Test
    public void readContainerMemoryLimitReturnsEmptyWhenNoCgroupFilesExist() throws Exception {
        Path dir = Files.createTempDirectory("memory-monitor-limit-empty");

        OptionalLong limit = MemoryMonitor.readContainerMemoryLimit(dir.resolve("missing-v2"), dir.resolve("missing-v1"));

        assertFalse(limit.isPresent());
    }

    @Test
    public void readContainerMemoryUsagePrefersCgroupV2ThenV1() throws Exception {
        Path dir = Files.createTempDirectory("memory-monitor-usage");
        Path v2 = dir.resolve("memory.current");
        Path v1 = dir.resolve("memory.usage_in_bytes");
        Files.writeString(v2, "111\n", StandardCharsets.UTF_8);
        Files.writeString(v1, "222\n", StandardCharsets.UTF_8);
        assertEquals(111L, MemoryMonitor.readContainerMemoryUsage(v2, v1).getAsLong());

        Files.delete(v2);
        assertEquals(222L, MemoryMonitor.readContainerMemoryUsage(v2, v1).getAsLong());
    }

    @Test
    public void readInactiveFileFromMemoryStat() throws Exception {
        Path stat = Files.createTempFile("memory-monitor-stat", ".txt");
        Files.writeString(stat, "anon 1000\nfile 4000\ninactive_file 3000\nactive_file 1000\n", StandardCharsets.UTF_8);

        OptionalLong inactive = MemoryMonitor.readInactiveFile(stat);

        assertTrue(inactive.isPresent());
        assertEquals(3000L, inactive.getAsLong());
    }

    @Test
    public void readContainerInactiveFilePrefersCgroupV2Stat() throws Exception {
        Path dir = Files.createTempDirectory("memory-monitor-inactive");
        Path v2 = dir.resolve("memory.stat");
        Path v1 = dir.resolve("memory-v1.stat");
        Files.writeString(v2, "inactive_file 111\n", StandardCharsets.UTF_8);
        Files.writeString(v1, "inactive_file 222\n", StandardCharsets.UTF_8);

        assertEquals(111L, MemoryMonitor.readContainerInactiveFile(v2, v1).getAsLong());
    }

    @Test
    public void workingSetSubtractsInactiveFileButNeverBelowZero() {
        assertEquals(700L, MemoryMonitor.workingSetBytes(1000L, OptionalLong.of(300L)));
        assertEquals(1000L, MemoryMonitor.workingSetBytes(1000L, OptionalLong.empty()));
        assertEquals(0L, MemoryMonitor.workingSetBytes(1000L, OptionalLong.of(1500L)));
    }

    @Test
    public void shouldWarnOnlyAboveThresholdAndAfterInterval() {
        assertFalse(MemoryMonitor.shouldWarn(85.0, 1000L, 0L, 0L));
        assertTrue(MemoryMonitor.shouldWarn(85.1, 1000L, 0L, 0L));
        assertFalse(MemoryMonitor.shouldWarn(90.0, 1000L, 900L, 200L));
        assertTrue(MemoryMonitor.shouldWarn(90.0, 1200L, 900L, 200L));
    }

    @Test
    public void warningMessagesPreserveOperationalGuidance() {
        assertEquals(
            "WARNING: Container working set is 90.5% (905 / 1000 MiB); raw cgroup memory is 990 MiB, inactive file cache is 85 MiB. High non-reclaimable memory may indicate heap, off-heap, or native allocations; consider reducing population/output or increasing container memory.",
            MemoryMonitor.containerWarning(90.5, 905, 1000, 990, OptionalLong.of(85))
        );
        assertEquals(
            "WARNING: Java heap is 91.0% (91 / 100 MiB). Heap exhaustion likely - consider reducing population size or batch size.",
            MemoryMonitor.heapWarning(91.0, 91, 100)
        );
    }

    @Test
    public void numericSnapshotSeparatesHeapContainerAndReclaimableCache() throws Exception {
        container(2000, 1500, 500);
        try (MemoryMonitor monitor = monitor(message -> fail(message), 700)) {
            MemoryMonitor.Snapshot sample = monitor.sample();
            assertEquals(700, sample.heapUsedBytes());
            assertEquals(1000, sample.heapCommittedBytes());
            assertEquals(1000, sample.heapMaxBytes());
            assertEquals(2000, sample.containerLimitBytes().getAsLong());
            assertEquals(1500, sample.containerUsedBytes().getAsLong());
            assertEquals(500, sample.inactiveFileBytes().getAsLong());
            assertEquals(1000, sample.containerWorkingSetBytes().getAsLong());
        }
    }

    @Test
    public void samplesSeeLiveLimitIncreasesAndUpdatedUsage() throws Exception {
        container(1000, 950, 100);
        try (MemoryMonitor monitor = monitor(message -> fail(message), 700)) {
            assertEquals(1000, monitor.sample().containerLimitBytes().getAsLong());
            container(2000, 1200, 300);
            MemoryMonitor.Snapshot changed = monitor.sample();
            assertEquals(2000, changed.containerLimitBytes().getAsLong());
            assertEquals(900, changed.containerWorkingSetBytes().getAsLong());
            assertEquals(1000, changed.heapMaxBytes());
        }
    }

    @Test
    public void missingContainerSignalsNeverBecomeHeapBasedContainerMeasurements() {
        try (MemoryMonitor monitor = monitor(message -> fail(message), 950)) {
            MemoryMonitor.Snapshot sample = monitor.sample();
            assertEquals(950, sample.heapUsedBytes());
            assertTrue(sample.containerLimitBytes().isEmpty());
            assertTrue(sample.containerUsedBytes().isEmpty());
            assertTrue(sample.containerWorkingSetBytes().isEmpty());
        }
    }

    @Test
    public void malformedSignalsAreBoundedDiagnosticallyAndRecoverOnLaterSamples() throws Exception {
        container(1000, 950, 100);
        Files.writeString(root.resolve("memory.max"), "0");
        Files.writeString(root.resolve("memory.current"), "-1");
        List<String> logs = new CopyOnWriteArrayList<>();
        try (MemoryMonitor monitor = monitor(logs::add, 700)) {
            for (int i = 0; i < 3; i++) {
                MemoryMonitor.Snapshot sample = monitor.sample();
                assertTrue(sample.containerLimitBytes().isEmpty());
                assertTrue(sample.containerUsedBytes().isEmpty());
            }
            assertEquals(2, logs.size());
            assertTrue(logs.stream().noneMatch(message -> message.contains(root.toString())));
            container(2000, 1000, 100);
            assertEquals(900, monitor.sample().containerWorkingSetBytes().getAsLong());
            assertEquals(2, logs.size());
        }
    }

    @Test
    public void unlimitedContainerLimitRemainsUnknownRatherThanAssumingHeapMaximum() throws Exception {
        container(1000, 950, 100);
        Files.writeString(root.resolve("memory.max"), "max");
        try (MemoryMonitor monitor = monitor(message -> fail(message), 700)) {
            MemoryMonitor.Snapshot sample = monitor.sample();
            assertTrue(sample.containerLimitBytes().isEmpty());
            assertEquals(950, sample.containerUsedBytes().getAsLong());
        }
    }

    @Test
    public void unavailableCacheDoesNotSubtractInventedMemory() throws Exception {
        container(1000, 950, 100);
        Files.writeString(root.resolve("memory.stat"), "inactive_file -1");
        try (MemoryMonitor monitor = monitor(message -> fail(message), 700)) {
            MemoryMonitor.Snapshot sample = monitor.sample();
            assertTrue(sample.inactiveFileBytes().isEmpty());
            assertEquals(950, sample.containerWorkingSetBytes().getAsLong());
        }
    }

    @Test
    public void backgroundWarningsUseCallerSinkAndMonitorCanStopAndRestart() throws Exception {
        container(1000, 950, 0);
        List<String> logs = new CopyOnWriteArrayList<>();
        Semaphore warnings = new Semaphore(0);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try (MemoryMonitor monitor = monitor(message -> {
            worker.set(Thread.currentThread());
            logs.add(message);
            warnings.release();
        }, 950)) {
            monitor.start();
            monitor.start();
            assertTrue(warnings.tryAcquire(2, 3, TimeUnit.SECONDS));
            Thread first = worker.get();
            assertTrue(first.isDaemon());
            assertEquals("microsim-memory-monitor", first.getName());
            monitor.stop();
            assertFalse(first.isAlive());
            assertEquals(2, logs.size());
            monitor.start();
            assertTrue(warnings.tryAcquire(2, 3, TimeUnit.SECONDS));
            monitor.stop();
            assertNotSame(first, worker.get());
            assertFalse(worker.get().isAlive());
            assertEquals(4, logs.size());
            assertEquals(2, logs.stream().filter(message -> message.startsWith("WARNING: Java heap")).count());
            assertEquals(2, logs.stream().filter(message -> message.startsWith("WARNING: Container")).count());
        }
    }

    @Test
    public void heapWarningStillWorksWhenContainerFilesAreAbsent() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        Semaphore warnings = new Semaphore(0);
        try (MemoryMonitor monitor = monitor(message -> {
            logs.add(message);
            warnings.release();
        }, 950)) {
            monitor.start();
            assertTrue(warnings.tryAcquire(3, TimeUnit.SECONDS));
            monitor.stop();
            assertEquals(1, logs.size());
            assertTrue(logs.getFirst().startsWith("WARNING: Java heap"));
        }
    }
}
