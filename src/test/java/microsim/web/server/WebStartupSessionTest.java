/* (C) Copyright 2026, by Ross Richardson
 * Startup confirmation, cancellation, stale reviews and failure recovery tests.
 * @author ross richardson
 */
package microsim.web.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class WebStartupSessionTest {
    static class Provider implements WebStartupProvider {
        int version, writes; boolean ready, fail;
        public Map<String, Object> describe() { return Map.of(); }
        public Map<String, Object> review(Map<String, Boolean> choices) { return Map.of("version", version); }
        public void prepare(Map<String, Boolean> choices, Consumer<String> progress) {
            writes++; progress.accept("working");
            if (fail) throw new IllegalStateException("import failed");
            ready = true;
        }
        public void validateBuild() { if (!ready) throw new IllegalStateException("not ready"); }
    }
    @Test void reviewAndCancelNeverPrepareAndBuildCannotBypass() throws Exception {
        var p = new Provider(); var s = new WebStartupSession(p);
        s.review(Map.of()); s.status();
        assertEquals(0, p.writes);
        assertThrows(IllegalStateException.class, s::requireReady);
    }
    @Test void changedReviewAndDuplicateConfirmationAreRejected() throws Exception {
        var p = new Provider(); var s = new WebStartupSession(p);
        String token = (String)s.review(Map.of()).get("token");
        p.version++;
        assertThrows(IllegalStateException.class, () -> s.confirm(token, Runnable::run, new ReentrantLock(), x -> {}));
        assertEquals(0, p.writes);
        String fresh = (String)s.review(Map.of()).get("token");
        s.confirm(fresh, Runnable::run, new ReentrantLock(), x -> {});
        assertThrows(IllegalStateException.class, () -> s.confirm(fresh, Runnable::run, new ReentrantLock(), x -> {}));
        s.requireReady(); s.buildStarting();
        assertThrows(IllegalStateException.class, () -> s.review(Map.of()));
        s.requireReady(); // reset/rebuild keeps the confirmed configuration
    }
    @Test void pendingPreparationBlocksBuildAndReportsFailure() throws Exception {
        var p = new Provider(); p.fail = true; var s = new WebStartupSession(p);
        var jobs = new ArrayList<Runnable>();
        String token = (String)s.review(Map.of()).get("token");
        s.confirm(token, jobs::add, new ReentrantLock(), x -> {});
        assertEquals("preparing", s.status().get("state"));
        assertThrows(IllegalStateException.class, s::requireReady);
        jobs.getFirst().run();
        assertEquals("failed", s.status().get("state"));
        assertEquals("import failed", s.status().get("message"));
        assertThrows(IllegalStateException.class, s::requireReady);
        p.fail = false;
        s.confirm((String)s.review(Map.of()).get("token"), Runnable::run, new ReentrantLock(), x -> {});
        s.requireReady();
    }
    @Test void inputsChangingWhileWorkerWaitsPreventWrites() throws Exception {
        var p = new Provider(); var s = new WebStartupSession(p); var jobs = new ArrayList<Runnable>();
        s.confirm((String)s.review(Map.of()).get("token"), jobs::add, new ReentrantLock(), x -> {});
        p.version++;
        jobs.getFirst().run();
        assertEquals(0, p.writes);
        assertEquals("failed", s.status().get("state"));
    }
}
