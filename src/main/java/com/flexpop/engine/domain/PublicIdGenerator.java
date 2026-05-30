package com.flexpop.engine.domain;

import java.security.SecureRandom;

/**
 * Generates human-readable, URL-safe public IDs.
 *   Transactions:       FP-{CCY}-{6 char Crockford Base32}    e.g. FP-NPR-7F3A92
 *   Sessions:           SES-{6 char Crockford Base32}         e.g. SES-7F3A92
 *
 * Crockford Base32 alphabet excludes I, L, O, U — avoiding misreads in support tickets.
 * Six chars over 32 symbols = ~1.07B possibilities; unique index handles the tiny
 * collision risk by retry at the call site.
 */
public final class PublicIdGenerator {

    private static final char[] CROCKFORD =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int CROCKFORD_LEN = CROCKFORD.length;
    private static final SecureRandom RNG = new SecureRandom();

    private PublicIdGenerator() { }

    public static String forTransaction(Currency currency) {
        return "FP-" + currency.name() + "-" + random(6);
    }

    public static String forRefund(Currency currency) {
        return "RF-" + currency.name() + "-" + random(6);
    }

    public static String forSession() {
        return "SES-" + random(6);
    }

    public static String forEvent() {
        return "EVT-" + random(10);
    }

    public static String forWebhookDelivery() {
        return "WH-" + random(10);
    }

    private static String random(int n) {
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = CROCKFORD[RNG.nextInt(CROCKFORD_LEN)];
        }
        return new String(out);
    }
}
