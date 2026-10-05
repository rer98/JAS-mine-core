package microsim.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import microsim.engine.MultiRun;
import microsim.engine.SimulationEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/* (C) Copyright 2026, by Ross Richardson
 *
 * Runs the real MultiRun thread in a disposable JVM with a fictional model.
 * Verifies desktop execution stays unmonitored and explicit launcher opt-in
 * starts the shared daemon before model execution, without starting a web server.
 *
 * @author ross richardson
 */

class MultiRunMemoryMonitorTest {
    @TempDir
    Path root;

    @Test
    void desktopMultiRunDoesNotStartMonitorByDefault() throws Exception {
        runProbe(null, false);
    }

    @Test
    void explicitFalseDoesNotStartMonitor() throws Exception {
        runProbe("false", false);
    }

    @Test
    void explicitLauncherOptInStartsMonitorBeforeModelExecution() throws Exception {
        runProbe("true", true);
    }

    private void runProbe(String setting, boolean expectedMonitoring) throws Exception {
        Path log = root.resolve("multirun.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java, "-Xmx64m", "-Djava.awt.headless=true"));
        if (setting != null) command.add("-Djasmine.memory.monitor.enabled=" + setting);
        command.addAll(List.of("-cp", classpath, Probe.class.getName(), Boolean.toString(expectedMonitoring)));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(name);
        }
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Disposable MultiRun did not finish");
            assertEquals(0, process.exitValue(), Files.readString(log));
            String status = expectedMonitoring ? "active" : "disabled";
            assertTrue(Files.readString(log).contains("shared-memory-monitor-" + status), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static final class Probe extends MultiRun {
        private static boolean expectedMonitoring;

        public static void main(String[] args) {
            expectedMonitoring = Boolean.parseBoolean(args[0]);
            new Probe().start();
        }

        @Override
        public synchronized void go() {
            boolean monitoring = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.isDaemon()
                    && thread.getName().equals("microsim-memory-monitor"));
            if (monitoring != expectedMonitoring) {
                throw new IllegalStateException("Unexpected shared memory monitor state: " + monitoring);
            }
            System.out.println("shared-memory-monitor-" + (monitoring ? "active" : "disabled"));
            toBeContinued = false;
            executionActive = false;
        }

        @Override
        public boolean nextModel() { return false; }

        @Override
        public String setupRunLabel() { return "fictional-monitor-probe"; }

        @Override
        public void buildExperiment(SimulationEngine engine) { }
    }
}
