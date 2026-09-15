package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for ApiErrors.
 * Verifies that expected errors keep their explicit messages while unexpected
 * failures expose only an incident identifier and retain details privately.
 *
 * @author ross richardson
 *
 */

public class ApiErrorsTest {
    @Test
    public void errorBodyUsesStandardErrorKey() {
        assertEquals(Map.of("error", "Something went wrong"), ApiErrors.errorBody("Something went wrong"));
    }

    @Test
    public void internalErrorBodyContainsGenericMessageAndIncidentIdOnly() {
        Map<String, String> body = ApiErrors.internalErrorBody("error-123");

        assertEquals(ApiErrors.INTERNAL_ERROR_MESSAGE, body.get("error"));
        assertEquals("error-123", body.get("errorId"));
        assertEquals(2, body.size());
        assertFalse(body.toString().contains("restricted row value"));
    }

    @Test
    public void reportDiagnosticWritesDetailsToPrivateSink() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        IllegalStateException error = new IllegalStateException("restricted row value");

        String errorId = ApiErrors.reportDiagnostic(sink, "path=/simulation/build", error);
        String diagnostic = bytes.toString(StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> UUID.fromString(errorId));
        assertTrue(diagnostic.contains("errorId=" + errorId));
        assertTrue(diagnostic.contains("path=/simulation/build"));
        assertTrue(diagnostic.contains("IllegalStateException: restricted row value"));
        assertTrue(diagnostic.contains("ApiErrorsTest.reportDiagnosticWritesDetailsToPrivateSink"));
    }
}
