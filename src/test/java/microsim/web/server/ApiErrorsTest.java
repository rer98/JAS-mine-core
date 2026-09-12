package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for ApiErrors.
 * Verifies the focused JAS-mine Web helper behaviour implemented by ApiErrors
 * so SimulationServer can delegate that concern without changing endpoint contracts.
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
    public void messageForUsesExceptionMessageWhenPresent() {
        assertEquals("bad input", ApiErrors.messageFor(new IllegalArgumentException("bad input")));
    }

    @Test
    public void messageForFallsBackToClassNameWhenMessageIsNull() {
        assertEquals("IllegalStateException", ApiErrors.messageFor(new IllegalStateException()));
    }
}
