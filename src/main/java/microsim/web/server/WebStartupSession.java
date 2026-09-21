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
    private Map<String, Object> choices;
    private Map<String, Object> reviewed;
    private boolean buildStarted;

    public WebStartupSession(WebStartupProvider provider) { this.provider = provider; }

    public synchronized Map<String, Object> status() {
        if (state.equals("ready") && !buildStarted) {
            try { provider.validateBuild(); }
            catch (Exception e) { state = "required"; message = e.getMessage(); }
        }
        return Map.of("supported", true, "state", state, "message", message,
                "locked", buildStarted, "definition", provider.describe(), "selection", choices == null ? Map.of() : choices);
    }

    public synchronized Map<String, Object> review(Map<String, ?> selected) throws Exception {
        if (buildStarted || state.equals("preparing"))
            throw new IllegalStateException("Startup cannot be changed after Build starts or during preparation.");
        Map<String, Object> copy = new LinkedHashMap<>(selected);
        copy = Collections.unmodifiableMap(copy);
        var result = provider.reviewRequest(copy);
        choices = copy;
        reviewed = new LinkedHashMap<>(result);
        token = UUID.randomUUID().toString();
        state = "required";
        message = "Review the selected operations and confirm before Build.";
        return Map.of("token", token, "review", reviewed);
    }

    public synchronized void upload(String path, java.io.InputStream body) throws Exception {
        if (buildStarted || state.equals("preparing"))
            throw new IllegalStateException("Startup uploads are locked after Build starts or during preparation.");
        token = null;
        state = "required";
        provider.upload(path, body);
        message = "Candidate uploaded. Review the selected operations before preparation.";
    }

    public synchronized void discardUploads() throws Exception {
        if (buildStarted || state.equals("preparing")) throw new IllegalStateException("Startup inputs are locked");
        token = null; state = "required"; provider.discardUploads();
    }
    /** Cancel is client-side: review has no model or filesystem side effects. */
    public synchronized void confirm(String suppliedToken, Executor executor, Lock lock,
            Consumer<String> logger) throws Exception {
        if (buildStarted || state.equals("preparing") || token == null || !token.equals(suppliedToken))
            throw new IllegalStateException("Startup review expired. Review the choices again.");
        if (!reviewed.equals(provider.reviewRequest(choices))) {
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
                    if (!expected.equals(provider.reviewRequest(selected)))
                        throw new IllegalStateException("Startup inputs changed. Review the choices again.");
                    provider.prepareRequest(selected, text -> {
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
