package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for MemoryMonitor.
 * Verifies the focused JAS-mine Web helper behaviour implemented by MemoryMonitor
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class MemoryMonitorTest {
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
}
