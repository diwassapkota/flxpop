package com.flexpop.engine.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class HmacSigner {

    private static final String ALGO = "HmacSHA256";

    public String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC sign failed", e);
        }
    }

    /**
     * Constant-time comparison — never use String.equals() for signature checks.
     */
    public boolean verify(String body, String secret, String providedSignature) {
        if (providedSignature == null) return false;
        String expected = sign(body, secret);
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = providedSignature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
