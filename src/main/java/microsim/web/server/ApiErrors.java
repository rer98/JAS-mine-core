package microsim.web.server;

import io.javalin.http.Context;

import java.util.Map;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Shared JSON error response helpers for JAS-mine Web API endpoints.
 * Contains helpers for building standard error response bodies, selecting exception messages,
 * and writing JSON errors through Javalin contexts.
 *
 * @author ross richardson
 *
 */

/** Shared JSON error response helpers for API endpoints. */
public final class ApiErrors {
    private ApiErrors() {}

    public static Map<String, String> errorBody(String message) {
        return Map.of("error", message);
    }

    public static String messageFor(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    public static void jsonError(Context ctx, int status, String message) {
        ctx.status(status).json(errorBody(message));
    }

    public static void handleError(Context ctx, Exception e, boolean logStackTrace) {
        if (logStackTrace) e.printStackTrace();
        jsonError(ctx, 500, messageFor(e));
    }
}
