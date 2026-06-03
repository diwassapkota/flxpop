package com.flxpop.engine.adapter.fonepay;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FonepayTokenCacheTest {

    private static FonepayLoginService.LoginResult tokenValidFor(String value, long minutes) {
        return new FonepayLoginService.LoginResult(value, Instant.now().plus(minutes, ChronoUnit.MINUTES));
    }

    @Test
    void loginCalledOnceOnFirstUse_thenCached() {
        FonepayLoginService login = mock(FonepayLoginService.class);
        when(login.login()).thenReturn(tokenValidFor("tok-1", 60));

        FonepayTokenCache cache = new FonepayTokenCache(login);

        assertThat(cache.currentToken()).isEqualTo("tok-1");
        assertThat(cache.currentToken()).isEqualTo("tok-1");
        assertThat(cache.currentToken()).isEqualTo("tok-1");
        verify(login, times(1)).login();
    }

    @Test
    void tokenWithinSafetyMarginTriggersReLogin() {
        FonepayLoginService login = mock(FonepayLoginService.class);
        // Expires in 2 minutes — inside the 5-minute safety margin → must refresh.
        when(login.login())
                .thenReturn(tokenValidFor("tok-stale", 2))
                .thenReturn(tokenValidFor("tok-fresh", 60));

        FonepayTokenCache cache = new FonepayTokenCache(login);

        cache.currentToken();  // primes cache with the stale token
        assertThat(cache.currentToken()).isEqualTo("tok-fresh");
        verify(login, times(2)).login();
    }

    @Test
    void invalidateForcesReLogin() {
        FonepayLoginService login = mock(FonepayLoginService.class);
        when(login.login())
                .thenReturn(tokenValidFor("tok-1", 60))
                .thenReturn(tokenValidFor("tok-2", 60));

        FonepayTokenCache cache = new FonepayTokenCache(login);

        cache.currentToken();
        cache.invalidate();
        assertThat(cache.currentToken()).isEqualTo("tok-2");
        verify(login, times(2)).login();
    }

    @Test
    void concurrentCallersAllSeeOneLogin() throws Exception {
        FonepayLoginService login = mock(FonepayLoginService.class);
        when(login.login()).thenAnswer(invocation -> {
            Thread.sleep(40);
            return tokenValidFor("tok-A", 60);
        });

        FonepayTokenCache cache = new FonepayTokenCache(login);

        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                assertThat(cache.currentToken()).isEqualTo("tok-A");
                done.countDown();
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        verify(login, times(1)).login();
    }
}
