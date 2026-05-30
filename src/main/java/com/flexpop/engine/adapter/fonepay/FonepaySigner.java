package com.flexpop.engine.adapter.fonepay;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Signs Fonepay Intent API request bodies with SHA256withRSA, returning the
 * base64-encoded signature suitable for the {@code signature} header.
 *
 * <p>The Postman collection uses jsrsasign because JavaScript doesn't have
 * native RSA; Java does — no external dep needed. Constant-time comparison
 * is not relevant here (we only ever sign, never verify our own signatures).
 *
 * <p>PKCS8 key bytes are decoded once on first use and cached.
 */
@Component
public class FonepaySigner {

    private static final String ALGORITHM = "SHA256withRSA";

    private final FonepayProperties props;
    private volatile PrivateKey cachedKey;

    public FonepaySigner(FonepayProperties props) {
        this.props = props;
    }

    public String sign(String body) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(privateKey());
            signer.update(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Fonepay signing failed: " + e.getMessage(), e);
        }
    }

    private PrivateKey privateKey() throws GeneralSecurityException {
        PrivateKey k = cachedKey;
        if (k != null) return k;
        synchronized (this) {
            if (cachedKey != null) return cachedKey;
            String b64 = props.privateKeyBase64Pkcs8();
            if (b64 == null || b64.isBlank()) {
                throw new IllegalStateException(
                        "Fonepay private key not configured — set FONEPAY_PRIVATE_KEY_B64 to a PKCS8 base64 key");
            }
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(b64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Fonepay private key is not valid base64: " + e.getMessage(), e);
            }
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            cachedKey = KeyFactory.getInstance("RSA").generatePrivate(spec);
            return cachedKey;
        }
    }
}
