package microsim.web.server;

import io.javalin.http.Context;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Shared JSON error response helpers for JAS-mine Web API endpoints.
 * Keeps unexpected exception details in a private diagnostic sink while returning
 * a browser-safe message and incident identifier.
 *
 * @author ross richardson
 *
 */

/** Shared JSON response and private-diagnostic helpers for API errors. */
public final class ApiErrors {
    public static final String INTERNAL_ERROR_MESSAGE = "The simulation encountered an internal error.";

    private ApiErrors() {}

    public static Map<String, String> errorBody(String message) {
        return Map.of("error", message);
    }

    public static Map<String, String> internalErrorBody(String errorId) {
        return Map.of(
            "error", INTERNAL_ERROR_MESSAGE,
            "errorId", errorId
        );
    }

    public static void jsonError(Context ctx, int status, String message) {
        ctx.status(status).json(errorBody(message));
    }

    public static void handleError(Context ctx, Exception error, PrintStream diagnosticSink) {
        if (error instanceof DiagnosticLimitException) {
            ctx.status(422).json(Map.of("error", error.getMessage(), "code", "diagnostic_limit"));
            return;
        }
        String path;
        try {
            path = ctx.path();
        } catch (RuntimeException ignored) {
            path = "<unavailable>";
        }
        String errorId = reportDiagnostic(diagnosticSink, "path=" + path, error);
        ctx.status(500).json(internalErrorBody(errorId));
    }

    public static String reportDiagnostic(PrintStream diagnosticSink, String context, Throwable error) {
        String errorId = UUID.randomUUID().toString();
        SafeDiagnostics.incident(errorId);
        writeDiagnostic(diagnosticSink, errorId, context, error);
        return errorId;
    }

    static void writeDiagnostic(PrintStream diagnosticSink, String errorId, String context, Throwable error) {
        if (diagnosticSink == null) return;
        diagnosticSink.printf(
            "%s [JAS-mine Web internal error] errorId=%s %s%n",
            Instant.now(), errorId, context
        );
        error.printStackTrace(diagnosticSink);
        diagnosticSink.flush();
    }
}
