package com.flxpop.engine.domain;

import java.util.Set;

public enum Gateway {
    FONEPAY(Country.NP),
    ESEWA(Country.NP),
    UPI(Country.IN),
    PAYTM(Country.IN),
    TNG(Country.MY),
    FPX(Country.MY),
    PROMPTPAY(Country.TH),
    TRUEMONEY(Country.TH);

    private final Country country;

    Gateway(Country country) {
        this.country = country;
    }

    public Country country() {
        return country;
    }

    public static Set<Gateway> forCountry(Country c) {
        return Set.of(values()).stream()
                .filter(g -> g.country == c)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
