package com.flxpop.engine.domain;

public enum Currency {
    NPR(2),
    INR(2),
    MYR(2),
    THB(2);

    private final int minorUnitScale;

    Currency(int minorUnitScale) {
        this.minorUnitScale = minorUnitScale;
    }

    public int minorUnitScale() {
        return minorUnitScale;
    }
}
