package microsim.web.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Hard-reset secret validation helper for the Java simulation backend.
 * Checks the X-Hard-Reset-Secret header using constant-time comparison and reports whether
 * a forced container restart is authorised.
 *
 * @author ross richardson
 *
 */

/** Constant-time hard-reset secret validation. */
public final class HardResetAuth {
    public static final String HEADER = "X-Hard-Reset-Secret";

    private HardResetAuth() {}

    public record Result(boolean allowed, String message) {}

    public static Result check(String configuredSecret, String suppliedSecret) {
        if (configuredSecret == null || configuredSecret.isEmpty()) {
            return new Result(false, "Hard reset is not configured for this model");
        }
        String supplied = suppliedSecret == null ? "" : suppliedSecret.trim();
        if (!MessageDigest.isEqual(
                configuredSecret.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            return new Result(false, "Hard reset is not authorised");
        }
        return new Result(true, "");
    }
}
