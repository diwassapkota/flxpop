package com.flexpop.engine.adapter.esewa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the eSewa HMAC-SHA256 signing against the worked examples published in
 * the vendor doc (https://developer.esewa.com.np/pages/Epay). If eSewa's UAT
 * secret or the canonical message format ever drifts, these fail loudly.
 *
 * <p>Both expected signatures were reproduced independently with
 * {@code openssl dgst -sha256 -hmac '8gBm/:&EnhH.1/q'} before being pinned here.
 */
class EsewaSignerTest {

    /** UAT secret from the eSewa doc. */
    private static final String UAT_SECRET = "8gBm/:&EnhH.1/q";

    private EsewaSigner signer() {
        return new EsewaSigner(new EsewaProperties(null, UAT_SECRET, null, null, null, null, 0L));
    }

    @Test
    void matchesTheDocsRequestFormExample() {
        // The doc's HTML form example: total_amount=110, transaction_uuid=241028.
        String message = "total_amount=110,transaction_uuid=241028,product_code=EPAYTEST";
        assertThat(signer().sign(message))
                .isEqualTo("i94zsd3oXF6ZsSr/kGqT4sSzYQzjj1W/waxjWyRwaME=");
    }

    @Test
    void matchesTheDocsSuccessResponseExample() {
        // The doc's decoded success-callback example — note signed_field_names is
        // itself a signed field, so its comma-bearing value is part of the message.
        String message = "transaction_code=000AWEO,status=COMPLETE,total_amount=1000.0,"
                + "transaction_uuid=250610-162413,product_code=EPAYTEST,"
                + "signed_field_names=transaction_code,status,total_amount,transaction_uuid,product_code,signed_field_names";
        assertThat(signer().sign(message))
                .isEqualTo("62GcfZTmVkzhtUeh+QJ1AqiJrjoWWGof3U+eTPTZ7fA=");
    }

    @Test
    void verifyAcceptsAGenuineSignatureAndRejectsTampering() {
        EsewaSigner s = signer();
        String message = "total_amount=110,transaction_uuid=241028,product_code=EPAYTEST";
        String good = s.sign(message);

        assertThat(s.verify(message, good)).isTrue();
        assertThat(s.verify("total_amount=999,transaction_uuid=241028,product_code=EPAYTEST", good)).isFalse();
        assertThat(s.verify(message, null)).isFalse();
    }

    @Test
    void signingIsDeterministic() {
        EsewaSigner s = signer();
        assertThat(s.sign("total_amount=5,transaction_uuid=x-1,product_code=EPAYTEST"))
                .isEqualTo(s.sign("total_amount=5,transaction_uuid=x-1,product_code=EPAYTEST"));
    }
}
