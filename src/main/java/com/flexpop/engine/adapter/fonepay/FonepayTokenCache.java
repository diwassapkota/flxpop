package com.flexpop.engine.adapter.fonepay;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-process cache for the Fonepay Bearer token. The token TTL is ~1h per the
 * vendor spec; we refresh proactively at {@link #SAFETY_MARGIN} before expiry
 * to avoid racing the deadline.
 *
 * <p>Concurrent {@link #currentToken()} callers serialise on a single
 * {@link ReentrantLock}; the first wins the login round-trip, the rest read
 * the freshly-cached token after the lock releases.
 *
 * <p>Restart-tolerant by design: cache lives in memory only. A restart
 * triggers exactly one fresh login on first use — well within Fonepay's
 * expected request rate.
 */
@Component
public class FonepayTokenCache {

    private static final Duration SAFETY_MARGIN = Duration.ofMinutes(5);

    private final FonepayLoginService loginService;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();

    public FonepayTokenCache(FonepayLoginService loginService) {
        this.loginService = loginService;
    }

    public String currentToken() {
        CachedToken token = cached.get();
        if (isValid(token)) {
            return token.accessToken();
        }
        lock.lock();
        try {
            token = cached.get();
            if (isValid(token)) {
                return token.accessToken();
            }
            FonepayLoginService.LoginResult fresh = loginService.login();
            CachedToken next = new CachedToken(fresh.accessToken(), fresh.expiresAt());
            cached.set(next);
            return next.accessToken();
        } finally {
            lock.unlock();
        }
    }

    /** Drop the cached token. Next {@link #currentToken()} will re-login. */
    public void invalidate() {
        cached.set(null);
    }

    private static boolean isValid(CachedToken t) {
        return t != null && Instant.now().isBefore(t.expiresAt().minus(SAFETY_MARGIN));
    }

    private record CachedToken(String accessToken, Instant expiresAt) { }
}
