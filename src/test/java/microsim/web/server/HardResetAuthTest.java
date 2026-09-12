package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for HardResetAuth.
 * Verifies the focused JAS-mine Web helper behaviour implemented by HardResetAuth
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class HardResetAuthTest {
    @Test
    public void rejectsWhenHardResetIsNotConfigured() {
        HardResetAuth.Result missing = HardResetAuth.check(null, "secret");
        assertFalse(missing.allowed());
        assertEquals("Hard reset is not configured for this model", missing.message());

        HardResetAuth.Result empty = HardResetAuth.check("", "secret");
        assertFalse(empty.allowed());
        assertEquals("Hard reset is not configured for this model", empty.message());
    }

    @Test
    public void rejectsMissingOrWrongSuppliedSecret() {
        assertFalse(HardResetAuth.check("secret", null).allowed());
        assertFalse(HardResetAuth.check("secret", "wrong").allowed());
        assertEquals("Hard reset is not authorised", HardResetAuth.check("secret", "wrong").message());
    }

    @Test
    public void acceptsMatchingSecretAndTrimsSuppliedHeaderValue() {
        assertTrue(HardResetAuth.check("secret", "secret").allowed());
        assertTrue(HardResetAuth.check("secret", " secret ").allowed());
    }
}
