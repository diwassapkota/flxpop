package com.flexpop.engine.webhook;

import java.time.Duration;

/**
 * Outbound webhook delivery backoff schedule.
 * Attempt N (1-indexed) waits SCHEDULE[N-1] before its NEXT try.
 * After MAX_ATTEMPTS the delivery transitions to DEAD.
 */
public final class Backoff {

    private static final Duration[] SCHEDULE = new Duration[] {
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(8),
            Duration.ofHours(24)
    };

    public static final int MAX_ATTEMPTS = SCHEDULE.length;

    private Backoff() { }

    public static Duration delayAfter(int attemptsCompleted) {
        if (attemptsCompleted < 1) return Duration.ZERO;
        int idx = Math.min(attemptsCompleted - 1, SCHEDULE.length - 1);
        return SCHEDULE[idx];
    }
}
