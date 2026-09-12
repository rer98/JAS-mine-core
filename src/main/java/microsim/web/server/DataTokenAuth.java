package microsim.web.server;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Restricted-model data-plane token validator for JAS-mine Web.
 * Validates HMAC-signed model/session/expiry tokens used to protect Java data endpoints
 * in Cloud Run direct-data mode and VM Python-proxy mode.
 *
 * @author ross richardson
 *
 */

/** Validation for short-lived JAS-mine Web data-plane access tokens. */
public final class DataTokenAuth {
    public static final String HEADER = "X-Data-Token";

    private DataTokenAuth() {}

    public static boolean validateToken(String token, String modelId, String simId, String secret) {
        try {
            if (token == null || token.isBlank()) return false;

            String[] parts = token.split("\\.", 2);
            if (parts.length != 2) return false;

            long exp = Long.parseLong(parts[0]);
            long now = System.currentTimeMillis() / 1000L;
            if (exp < now) return false;

            String msg = modelId + "|" + simId + "|" + exp;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }
}
