package com.flxpop.engine.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler as off-limits to publishable ({@code pk_…}) keys.
 *
 * <p>Use on routes that change merchant-side state in ways a browser-side
 * actor should not perform: creating sessions, issuing refunds, mutating
 * webhook URLs, etc. {@code POST /v1/transactions} and
 * {@code GET /v1/transactions/{id}} stay unannotated — those are the only
 * routes a widget needs.
 *
 * <p>{@link SecretKeyOnlyInterceptor} reads this annotation off the handler
 * method and rejects requests carrying a {@link com.flxpop.engine.domain.ApiKeyKind#PUBLISHABLE}
 * key with HTTP 403.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresSecretKey {
}
