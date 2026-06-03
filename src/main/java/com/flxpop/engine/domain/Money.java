package com.flxpop.engine.domain;

import java.util.Objects;

/**
 * Value object: an amount in a single currency, stored as minor units (paisa/cents).
 * Never use BigDecimal or double for money in the engine.
 */
public record Money(long minor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (minor < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + minor);
        }
    }

    public static Money of(long minor, Currency currency) {
        return new Money(minor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(this.minor, other.minor), currency);
    }

    private void requireSameCurrency(Money other) {
        if (this.currency != other.currency) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }
}
