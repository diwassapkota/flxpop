package com.flexpop.engine.adapter.fonepay;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FonepaySignerTest {

    private static KeyPair keyPair;
    private static String privateKeyB64;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();
        privateKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    private FonepaySigner signer(String b64Key) {
        return new FonepaySigner(new FonepayProperties(
                null, null, null, b64Key, null, null, 0L));
    }

    @Test
    void signsAndVerifiesAgainstThePublicKey() throws Exception {
        String body = "{\"username\":\"acme\",\"password\":\"hunter2\"}";

        String sig = signer(privateKeyB64).sign(body);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(sig)));
    }

    @Test
    void tamperedBodyFailsVerification() throws Exception {
        String original = "{\"amount\":100}";
        String tampered = "{\"amount\":999999}";

        String sig = signer(privateKeyB64).sign(original);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(tampered.getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(Base64.getDecoder().decode(sig)));
    }

    @Test
    void cachedKeyProducesIdenticalSignatureForSameBody() {
        FonepaySigner s = signer(privateKeyB64);
        // RSA with SHA256 is deterministic — same key, same body, same signature.
        assertThat(s.sign("hello")).isEqualTo(s.sign("hello"));
    }

    @Test
    void missingPrivateKeyFailsLoudly() {
        FonepaySigner s = signer("");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> s.sign("body"));
        assertThat(ex.getMessage()).contains("FONEPAY_PRIVATE_KEY_B64");
    }

    @Test
    void malformedBase64FailsLoudly() {
        FonepaySigner s = signer("not-valid-base64!@#$");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> s.sign("body"));
        assertThat(ex.getMessage()).contains("not valid base64");
    }
}
