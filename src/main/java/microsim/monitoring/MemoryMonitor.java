package microsim.monitoring;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Shared JVM/container memory monitor for interactive and batch simulations.
 * Reads cgroup/JVM memory signals, estimates working-set pressure by subtracting inactive file cache,
 * and delivers throttled warnings through the caller's log sink. Monitoring
 * observes resource use; it does not change limits or simulation settings.
 *
 * @author ross richardson
 *
 */

/** JVM/container memory monitor for warning about high heap or cgroup working-set usage. */
public final class MemoryMonitor implements AutoCloseable {
    /** Explicit JVM opt-in for monitoring in the generic MultiRun execution thread. */
    public static final String MULTIRUN_ENABLE_PROPERTY = "jasmine.memory.monitor.enabled";
    public static final long DEFAULT_WARNING_INTERVAL_MS = 30_000L;
    public static final long DEFAULT_POLL_INTERVAL_MS = 10_000L;

    private final Consumer<String> log;
    private final Supplier<MemoryUsage> heapUsage;
    private final Path cgroupV2;
    private final Path cgroupV1;
    private final long warningIntervalMs;
    private final long pollIntervalMs;
    private Thread monitorThread;
    private final AtomicBoolean loggedMemoryLimitFallback = new AtomicBoolean();
    private final AtomicBoolean loggedMemoryUsageFallback = new AtomicBoolean();

    public MemoryMonitor(Consumer<String> log) {
        this(log, () -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(),
            Paths.get("/sys/fs/cgroup"), Paths.get("/sys/fs/cgroup/memory"),
            DEFAULT_WARNING_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    MemoryMonitor(Consumer<String> log, Supplier<MemoryUsage> heapUsage,
                  Path cgroupV2, Path cgroupV1, long warningIntervalMs, long pollIntervalMs) {
        this.log = Objects.requireNonNull(log);
        this.heapUsage = Objects.requireNonNull(heapUsage);
        this.cgroupV2 = Objects.requireNonNull(cgroupV2);
        this.cgroupV1 = Objects.requireNonNull(cgroupV1);
        if (warningIntervalMs <= 0 || pollIntervalMs <= 0) {
            throw new IllegalArgumentException("Memory monitor intervals must be positive");
        }
        this.warningIntervalMs = warningIntervalMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    /** Numeric observations; absent container values are never inferred from the heap. */
    public record Snapshot(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
                           OptionalLong containerLimitBytes, OptionalLong containerUsedBytes,
                           OptionalLong inactiveFileBytes) {
        public Snapshot {
            Objects.requireNonNull(containerLimitBytes);
            Objects.requireNonNull(containerUsedBytes);
            Objects.requireNonNull(inactiveFileBytes);
        }

        public OptionalLong containerWorkingSetBytes() {
            return containerUsedBytes.isPresent()
                ? OptionalLong.of(workingSetBytes(containerUsedBytes.getAsLong(), inactiveFileBytes))
                : OptionalLong.empty();
        }
    }

    /** Read current limits on every sample, including an operator-approved live increase. */
    public Snapshot sample() {
        MemoryUsage heap = heapUsage.get();
        return new Snapshot(heap.getUsed(), heap.getCommitted(), heap.getMax(),
            getContainerMemoryLimit(), getContainerMemoryUsage(), getContainerInactiveFile());
    }

    public synchronized void start() {
        if (monitorThread != null && monitorThread.isAlive()) return;

        monitorThread = new Thread(() -> {
            long[] lastContainerWarningAt = {0L};
            long[] lastHeapWarningAt = {0L};
            while (!Thread.interrupted()) {
                long now = System.currentTimeMillis();
                Snapshot snapshot = sample();

                if (snapshot.containerLimitBytes().isPresent() && snapshot.containerUsedBytes().isPresent()) {
                    long containerLimit = snapshot.containerLimitBytes().getAsLong();
                    long containerLimitMb = containerLimit / (1024 * 1024);
                    long containerUsed = snapshot.containerUsedBytes().getAsLong();
                    OptionalLong inactiveFile = snapshot.inactiveFileBytes();
                    long workingSet = snapshot.containerWorkingSetBytes().getAsLong();
                    long workingSetMb = workingSet / (1024 * 1024);
                    long containerUsedMb = containerUsed / (1024 * 1024);
                    OptionalLong inactiveFileMb = inactiveFile.isPresent()
                        ? OptionalLong.of(inactiveFile.getAsLong() / (1024 * 1024))
                        : OptionalLong.empty();
                    double workingSetPct = (double) workingSet / containerLimit * 100.0;

                    if (shouldWarn(workingSetPct, now, lastContainerWarningAt[0], warningIntervalMs)) {
                        lastContainerWarningAt[0] = now;
                        log.accept(containerWarning(workingSetPct, workingSetMb, containerLimitMb, containerUsedMb, inactiveFileMb));
                    }
                }

                long heapMax = snapshot.heapMaxBytes();
                long heapUsed = snapshot.heapUsedBytes();

                if (heapMax > 0) {
                    double heapPct = (double) heapUsed / heapMax * 100.0;
                    long heapUsedMb = heapUsed / (1024 * 1024);
                    long heapMaxMb  = heapMax  / (1024 * 1024);

                    if (shouldWarn(heapPct, now, lastHeapWarningAt[0], warningIntervalMs)) {
                        lastHeapWarningAt[0] = now;
                        log.accept(heapWarning(heapPct, heapUsedMb, heapMaxMb));
                    }
                }

                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "microsim-memory-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public synchronized void stop() {
        if (monitorThread == null) return;
        monitorThread.interrupt();
        if (monitorThread != Thread.currentThread()) {
            try {
                monitorThread.join(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private OptionalLong getContainerMemoryLimit() {
        try {
            OptionalLong limit = readContainerMemoryLimit(
                cgroupV2.resolve("memory.max"), cgroupV1.resolve("memory.limit_in_bytes")
            );
            return limit;
        } catch (Exception e) {
            if (loggedMemoryLimitFallback.compareAndSet(false, true)) {
                log.accept("Memory monitor: could not read cgroup memory limit (" + e.getClass().getSimpleName() + "); container pressure is unavailable, heap monitoring continues");
            }
        }
        return OptionalLong.empty();
    }

    private OptionalLong getContainerMemoryUsage() {
        try {
            OptionalLong usage = readContainerMemoryUsage(
                cgroupV2.resolve("memory.current"), cgroupV1.resolve("memory.usage_in_bytes")
            );
            return usage;
        } catch (Exception e) {
            if (loggedMemoryUsageFallback.compareAndSet(false, true)) {
                log.accept("Memory monitor: could not read cgroup memory usage (" + e.getClass().getSimpleName() + "); container pressure is unavailable, heap monitoring continues");
            }
        }

        return OptionalLong.empty();
    }

    private OptionalLong getContainerInactiveFile() {
        try {
            return readContainerInactiveFile(
                cgroupV2.resolve("memory.stat"), cgroupV1.resolve("memory.stat")
            );
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    static OptionalLong readContainerMemoryLimit(Path cgroupV2, Path cgroupV1) throws IOException {
        if (Files.exists(cgroupV2)) {
            String val = Files.readString(cgroupV2).trim();
            if (!val.equals("max")) return OptionalLong.of(positiveBytes(val));
        }
        if (Files.exists(cgroupV1)) return OptionalLong.of(positiveBytes(Files.readString(cgroupV1).trim()));
        return OptionalLong.empty();
    }

    static OptionalLong readContainerMemoryUsage(Path cgroupV2, Path cgroupV1) throws IOException {
        if (Files.exists(cgroupV2)) return OptionalLong.of(nonnegativeBytes(Files.readString(cgroupV2).trim()));
        if (Files.exists(cgroupV1)) return OptionalLong.of(nonnegativeBytes(Files.readString(cgroupV1).trim()));
        return OptionalLong.empty();
    }

    static OptionalLong readContainerInactiveFile(Path cgroupV2Stat, Path cgroupV1Stat) throws IOException {
        if (Files.exists(cgroupV2Stat)) return readInactiveFile(cgroupV2Stat);
        if (Files.exists(cgroupV1Stat)) return readInactiveFile(cgroupV1Stat);
        return OptionalLong.empty();
    }

    static OptionalLong readInactiveFile(Path memoryStat) throws IOException {
        for (String line : Files.readAllLines(memoryStat)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equals("inactive_file")) {
                return OptionalLong.of(nonnegativeBytes(parts[1]));
            }
        }
        return OptionalLong.empty();
    }

    private static long positiveBytes(String value) {
        long bytes = nonnegativeBytes(value);
        if (bytes == 0) throw new IllegalArgumentException("Memory limit must be positive");
        return bytes;
    }

    private static long nonnegativeBytes(String value) {
        long bytes = Long.parseLong(value);
        if (bytes < 0) throw new IllegalArgumentException("Memory bytes must be nonnegative");
        return bytes;
    }

    static long workingSetBytes(long usageBytes, OptionalLong inactiveFileBytes) {
        long inactive = inactiveFileBytes.isPresent() ? inactiveFileBytes.getAsLong() : 0L;
        return Math.max(0L, usageBytes - inactive);
    }

    static boolean shouldWarn(double pct, long now, long lastWarningAt, long intervalMs) {
        return pct > 85.0 && now - lastWarningAt >= intervalMs;
    }

    static String containerWarning(double workingSetPct, long workingSetMb, long limitMb, long rawUsedMb, OptionalLong inactiveFileMb) {
        String msg = "WARNING: Container working set is " + String.format(Locale.ROOT, "%.1f", workingSetPct)
            + "% (" + workingSetMb + " / " + limitMb + " MiB); raw cgroup memory is " + rawUsedMb + " MiB";
        if (inactiveFileMb.isPresent()) msg += ", inactive file cache is " + inactiveFileMb.getAsLong() + " MiB";
        return msg + ". High non-reclaimable memory may indicate heap, off-heap, or native allocations; consider reducing population/output or increasing container memory.";
    }

    static String heapWarning(double pct, long usedMb, long maxMb) {
        return "WARNING: Java heap is " + String.format(Locale.ROOT, "%.1f", pct)
            + "% (" + usedMb + " / " + maxMb + " MiB). "
            + "Heap exhaustion likely - consider reducing population size or batch size.";
    }
}
