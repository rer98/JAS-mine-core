package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for DataTokenAuth.
 * Verifies the focused JAS-mine Web helper behaviour implemented by DataTokenAuth
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class DataTokenAuthTest {
    @Test
    public void validTokenIsAccepted() throws Exception {
        long exp = System.currentTimeMillis() / 1000L + 3600;
        String token = token("model-a", "sim-a", exp, "secret");
        assertTrue(DataTokenAuth.validateToken(token, "model-a", "sim-a", "secret"));
    }

    @Test
    public void invalidExpiredAndMalformedTokensAreRejected() throws Exception {
        long future = System.currentTimeMillis() / 1000L + 3600;
        long past = System.currentTimeMillis() / 1000L - 10;
        assertFalse(DataTokenAuth.validateToken(token("model-a", "sim-a", future, "secret"), "model-a", "other-sim", "secret"));
        assertFalse(DataTokenAuth.validateToken(token("model-a", "sim-a", past, "secret"), "model-a", "sim-a", "secret"));
        assertFalse(DataTokenAuth.validateToken("not-a-token", "model-a", "sim-a", "secret"));
        assertFalse(DataTokenAuth.validateToken(null, "model-a", "sim-a", "secret"));
    }

    private static String token(String modelId, String simId, long exp, String secret) throws Exception {
        String msg = modelId + "|" + simId + "|" + exp;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return exp + "." + HexFormat.of().formatHex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
    }
}
