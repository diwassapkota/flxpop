package com.flxpop.engine.auth;

/**
 * Request-attribute keys set by ApiKeyAuthFilter and read by MerchantContext.
 * Kept in one place so the filter and the resolver agree without coupling.
 */
public final class AuthAttributes {

    public static final String MERCHANT     = "flxpop.auth.merchant";
    public static final String API_KEY_ID   = "flxpop.auth.apiKeyId";
    public static final String API_KEY_KIND = "flxpop.auth.apiKeyKind";

    private AuthAttributes() { }
}
