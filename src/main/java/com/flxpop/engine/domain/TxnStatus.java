package com.flxpop.engine.domain;

import java.util.EnumSet;
import java.util.Set;

public enum TxnStatus {
    CREATED,
    ROUTED,
    PENDING,
    SETTLED,
    FAILED,
    EXPIRED,
    REFUNDED;

    private static final Set<TxnStatus> TERMINAL =
            EnumSet.of(SETTLED, FAILED, EXPIRED, REFUNDED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
