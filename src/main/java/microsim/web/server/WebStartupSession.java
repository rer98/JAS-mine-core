/* (C) Copyright 2026, by Ross Richardson
 * Confirmation and asynchronous preparation state for an optional model startup flow.
 * @author ross richardson
 */
package microsim.web.server;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

public final class WebStartupSession {
    private final WebStartupProvider provider;
    private String state = "required", message = "Choose startup options.";
    private String token;
    private Map<String, Boolean> choices;
    private Map<String, Object> reviewed;
    private boolean buildStarted;

    public WebStartupSession(WebStartupProvider provider) { this.provider = provider; }

    public synchronized Map<String, Object> status() {
        if (state.equals("ready") && !buildStarted) {
            try { provider.validateBuild(); }
            catch (Exception e) { state = "required"; message = e.getMessage(); }
        }
        return Map.of("supported", true, "state", state, "message", message,
                "locked", buildStarted, "definition", provider.describe());
    }

    public synchronized Map<String, Object> review(Map<String, Boolean> selected) throws Exception {
        if (buildStarted || state.equals("preparing"))
            throw new IllegalStateException("Startup cannot be changed after Build starts or during preparation.");
        var copy = Map.copyOf(selected);
        var result = provider.review(copy);
        choices = copy;
        reviewed = new LinkedHashMap<>(result);
        token = UUID.randomUUID().toString();
        state = "required";
        message = "Review the selected operations and confirm before Build.";
        return Map.of("token", token, "review", reviewed);
    }

    /** Cancel is client-side: review has no model or filesystem side effects. */
    public synchronized void confirm(String suppliedToken, Executor executor, Lock lock,
            Consumer<String> logger) throws Exception {
        if (buildStarted || state.equals("preparing") || token == null || !token.equals(suppliedToken))
            throw new IllegalStateException("Startup review expired. Review the choices again.");
        if (!reviewed.equals(provider.review(choices))) {
            token = null;
            throw new IllegalStateException("Startup inputs changed. Review the choices again.");
        }
        var selected = choices;
        var expected = reviewed;
        token = null;
        state = "preparing";
        message = "Preparing inputs; this may take several minutes.";
        try {
            executor.execute(() -> {
                lock.lock();
                try {
                    if (!expected.equals(provider.review(selected)))
                        throw new IllegalStateException("Startup inputs changed. Review the choices again.");
                    provider.prepare(selected, text -> {
                        synchronized (this) { message = text; }
                        logger.accept(text);
                    });
                    provider.validateBuild();
                    synchronized (this) { state = "ready"; message = "Startup complete. Configure parameters and Build."; }
                } catch (Exception e) {
                    synchronized (this) { state = "failed"; message = Objects.toString(e.getMessage(), e.getClass().getSimpleName()); }
                    logger.accept("Startup failed: " + e.getMessage());
                } finally { lock.unlock(); }
            });
        } catch (RuntimeException e) {
            state = "failed"; message = "Preparation could not start. Review and retry."; throw e;
        }
    }

    public synchronized void requireReady() throws Exception {
        if (!state.equals("ready")) throw new IllegalStateException("Complete model startup before Build.");
        provider.validateBuild();
    }

    public synchronized void buildStarting() { buildStarted = true; }
}
