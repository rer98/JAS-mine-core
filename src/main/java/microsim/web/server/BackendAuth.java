/* (C) Copyright 2026, by Ross Richardson
 * Authentication for every session backend route, independent of catalogue login.
 * @author ross richardson
 */
package microsim.web.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public final class BackendAuth {
    public static final String HEADER = "X-Jasmine-Backend-Token";
    private final String secret;
    private final boolean localDevelopment;

    public BackendAuth(Map<String, String> env) {
        secret = env.getOrDefault("JASMINE_BACKEND_SECRET", env.getOrDefault("DATA_PLANE_SECRET", "")).trim();
        localDevelopment = "true".equals(env.get("JASMINE_LOCAL_DEVELOPMENT"));
        if (secret.length() < 32 && !localDevelopment)
            throw new IllegalStateException("Set a session JASMINE_BACKEND_SECRET (at least 32 characters), or explicitly enable JASMINE_LOCAL_DEVELOPMENT=true for local tests.");
    }

    public boolean accepts(String token) {
        if (secret.isEmpty()) return localDevelopment;
        return token != null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
