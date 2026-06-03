package com.flxpop.engine.domain;

/**
 * Two kinds of merchant API key:
 *
 * <ul>
 *   <li>{@link #SECRET} — full privilege; server-side use only. Allowed on
 *       every route (sessions, transactions, refunds, GET).</li>
 *   <li>{@link #PUBLISHABLE} — browser-safe; restricted by
 *       {@code @RequiresSecretKey} to routes that produce no irreversible
 *       merchant-side effect. Today: {@code POST /v1/transactions},
 *       {@code GET /v1/transactions/{id}}.</li>
 * </ul>
 *
 * <p>Per-merchant scoping (a publishable key can only act on the merchant's
 * own sessions/txns) is enforced separately by {@code MerchantContext} +
 * the services' ownership checks — that applies to both kinds.
 */
public enum ApiKeyKind {
    SECRET,
    PUBLISHABLE
}
