package microsim.web.server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.Consumer;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Container-aware memory monitor for JAS-mine Web simulation backends.
 * Reads cgroup/JVM memory signals, estimates working-set pressure by subtracting inactive file cache,
 * and emits throttled warnings to the simulation console.
 *
 * @author ross richardson
 *
 */

/** JVM/container memory monitor for warning about high heap or cgroup working-set usage. */
public final class MemoryMonitor {
    public static final long DEFAULT_WARNING_INTERVAL_MS = 30_000L;
    public static final long DEFAULT_POLL_INTERVAL_MS = 10_000L;

    private final Consumer<String> log;
    private final long warningIntervalMs;
    private final long pollIntervalMs;
    private Thread monitorThread;
    private boolean loggedMemoryLimitFallback = false;
    private boolean loggedMemoryUsageFallback = false;

    public MemoryMonitor(Consumer<String> log) {
        this(log, DEFAULT_WARNING_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    MemoryMonitor(Consumer<String> log, long warningIntervalMs, long pollIntervalMs) {
        this.log = log;
        this.warningIntervalMs = warningIntervalMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    public synchronized void start() {
        if (monitorThread != null && monitorThread.isAlive()) return;

        long containerLimit = getContainerMemoryLimit();
        long containerLimitMb = containerLimit / (1024 * 1024);
        MemoryMXBean heapBean = ManagementFactory.getMemoryMXBean();

        monitorThread = new Thread(() -> {
            long[] lastContainerWarningAt = {0L};
            long[] lastHeapWarningAt = {0L};
            while (!Thread.interrupted()) {
                long now = System.currentTimeMillis();

                if (containerLimit > 0) {
                    long containerUsed = getContainerMemoryUsage();
                    OptionalLong inactiveFile = getContainerInactiveFile();
                    long workingSet = workingSetBytes(containerUsed, inactiveFile);
                    long workingSetMb = workingSet / (1024 * 1024);
                    long containerUsedMb = containerUsed / (1024 * 1024);
                    OptionalLong inactiveFileMb = inactiveFile.isPresent()
                        ? OptionalLong.of(inactiveFile.getAsLong() / (1024 * 1024))
                        : OptionalLong.empty();
                    double workingSetPct = (double) workingSet / containerLimit * 100.0;

                    if (shouldWarn(workingSetPct, now, lastContainerWarningAt[0], warningIntervalMs)) {
                        lastContainerWarningAt[0] = now;
                        System.out.println(containerWarning(workingSetPct, workingSetMb, containerLimitMb, containerUsedMb, inactiveFileMb));
                    }
                }

                MemoryUsage heap = heapBean.getHeapMemoryUsage();
                long heapMax = heap.getMax();
                long heapUsed = heap.getUsed();

                if (heapMax > 0) {
                    double heapPct = (double) heapUsed / heapMax * 100.0;
                    long heapUsedMb = heapUsed / (1024 * 1024);
                    long heapMaxMb  = heapMax  / (1024 * 1024);

                    if (shouldWarn(heapPct, now, lastHeapWarningAt[0], warningIntervalMs)) {
                        lastHeapWarningAt[0] = now;
                        System.out.println(heapWarning(heapPct, heapUsedMb, heapMaxMb));
                    }
                }

                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public synchronized void stop() {
        if (monitorThread != null) monitorThread.interrupt();
    }

    long getContainerMemoryLimit() {
        try {
            OptionalLong limit = readContainerMemoryLimit(
                Paths.get("/sys/fs/cgroup/memory.max"),
                Paths.get("/sys/fs/cgroup/memory/memory.limit_in_bytes")
            );
            if (limit.isPresent()) return limit.getAsLong();
        } catch (Exception e) {
            if (!loggedMemoryLimitFallback) {
                loggedMemoryLimitFallback = true;
                log.accept("Memory monitor: could not read cgroup memory limit (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); falling back to JVM max heap");
            }
        }
        return Runtime.getRuntime().maxMemory();
    }

    long getContainerMemoryUsage() {
        try {
            OptionalLong usage = readContainerMemoryUsage(
                Paths.get("/sys/fs/cgroup/memory.current"),
                Paths.get("/sys/fs/cgroup/memory/memory.usage_in_bytes")
            );
            if (usage.isPresent()) return usage.getAsLong();
        } catch (Exception e) {
            if (!loggedMemoryUsageFallback) {
                loggedMemoryUsageFallback = true;
                log.accept("Memory monitor: could not read cgroup memory usage (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); falling back to JVM heap usage");
            }
        }

        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    OptionalLong getContainerInactiveFile() {
        try {
            return readContainerInactiveFile(
                Paths.get("/sys/fs/cgroup/memory.stat"),
                Paths.get("/sys/fs/cgroup/memory/memory.stat")
            );
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    static OptionalLong readContainerMemoryLimit(Path cgroupV2, Path cgroupV1) throws IOException {
        if (Files.exists(cgroupV2)) {
            String val = Files.readString(cgroupV2).trim();
            if (!val.equals("max")) return OptionalLong.of(Long.parseLong(val));
        }
        if (Files.exists(cgroupV1)) return OptionalLong.of(Long.parseLong(Files.readString(cgroupV1).trim()));
        return OptionalLong.empty();
    }

    static OptionalLong readContainerMemoryUsage(Path cgroupV2, Path cgroupV1) throws IOException {
        if (Files.exists(cgroupV2)) return OptionalLong.of(Long.parseLong(Files.readString(cgroupV2).trim()));
        if (Files.exists(cgroupV1)) return OptionalLong.of(Long.parseLong(Files.readString(cgroupV1).trim()));
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
                return OptionalLong.of(Long.parseLong(parts[1]));
            }
        }
        return OptionalLong.empty();
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
