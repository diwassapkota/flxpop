package com.flxpop.engine.domain;

public enum Country {
    NP(Currency.NPR),
    IN(Currency.INR),
    MY(Currency.MYR),
    TH(Currency.THB);

    private final Currency defaultCurrency;

    Country(Currency defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public Currency defaultCurrency() {
        return defaultCurrency;
    }
}
