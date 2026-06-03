package com.flexpop.engine.adapter.esewa;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * eSewa ePay v2 signing: Base64( HMAC-SHA256( message, secretKey ) ).
 *
 * <p>The same routine produces the {@code signature} we send on the payment
 * form / status check AND verifies the signature eSewa returns on its success
 * callback — only the message (the ordered {@code field=value,…} string built
 * from {@code signed_field_names}) differs.
 *
 * <p>Verified against the vendor doc's worked examples in {@code EsewaSignerTest}.
 */
@Component
public class EsewaSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final EsewaProperties props;

    public EsewaSigner(EsewaProperties props) {
        this.props = props;
    }

    public String sign(String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(props.secretKey().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("eSewa signing failed: " + e.getMessage(), e);
        }
    }

    /** Constant-time comparison of a freshly-computed signature against eSewa's. */
    public boolean verify(String message, String expectedBase64) {
        if (expectedBase64 == null) return false;
        return MessageDigest.isEqual(
                sign(message).getBytes(StandardCharsets.UTF_8),
                expectedBase64.getBytes(StandardCharsets.UTF_8));
    }
}
