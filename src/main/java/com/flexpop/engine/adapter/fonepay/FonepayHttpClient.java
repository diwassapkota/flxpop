package com.flexpop.engine.adapter.fonepay;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Two {@link RestClient} beans for the Fonepay Intent API:
 *
 * <ul>
 *   <li>{@code fonepayRawClient} — base URL + signing interceptor only.
 *       Used by {@link FonepayLoginService} to call {@code /login}; carrying
 *       the auth interceptor here would create a cycle since the auth header
 *       comes from a token cache that depends on the login service.</li>
 *   <li>{@code fonepayAuthedClient} — base URL + signing + Bearer-auth
 *       interceptor (skips {@code /login} defensively). Used by everything
 *       except the login call itself.</li>
 * </ul>
 *
 * <p>Both interceptors sign the body bytes as they exist on the wire — for
 * a GET, that's the empty string. The vendor's Postman collection signs
 * every request regardless of body, and Fonepay's verifier expects an
 * always-present {@code signature} header.
 */
@Configuration
public class FonepayHttpClient {

    private static final int CONNECT_TIMEOUT_MS = (int) Duration.ofSeconds(8).toMillis();
    private static final int READ_TIMEOUT_MS    = (int) Duration.ofSeconds(15).toMillis();

    @Bean
    public RestClient fonepayRawClient(FonepayProperties props, FonepaySigner signer) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(simpleFactory())
                .requestInterceptor(new SigningInterceptor(signer))
                .build();
    }

    @Bean
    public RestClient fonepayAuthedClient(FonepayProperties props,
                                          FonepaySigner signer,
                                          @Lazy FonepayTokenCache tokenCache) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(simpleFactory())
                .requestInterceptor(new SigningInterceptor(signer))
                .requestInterceptor(new BearerAuthInterceptor(tokenCache))
                .build();
    }

    private static SimpleClientHttpRequestFactory simpleFactory() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(CONNECT_TIMEOUT_MS);
        rf.setReadTimeout(READ_TIMEOUT_MS);
        return rf;
    }

    /** SHA256withRSA signature of the body bytes (UTF-8) in the {@code signature} header. */
    static final class SigningInterceptor implements ClientHttpRequestInterceptor {
        private final FonepaySigner signer;
        SigningInterceptor(FonepaySigner signer) { this.signer = signer; }

        @Override
        public ClientHttpResponse intercept(HttpRequest request,
                                            byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            String bodyStr = (body == null) ? "" : new String(body, StandardCharsets.UTF_8);
            request.getHeaders().add("signature", signer.sign(bodyStr));
            return execution.execute(request, body);
        }
    }

    /** Injects {@code Authorization: Bearer …} from the token cache, skipping {@code /login}. */
    static final class BearerAuthInterceptor implements ClientHttpRequestInterceptor {
        private final FonepayTokenCache cache;
        BearerAuthInterceptor(FonepayTokenCache cache) { this.cache = cache; }

        @Override
        public ClientHttpResponse intercept(HttpRequest request,
                                            byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            String path = request.getURI().getPath();
            boolean isLogin = path != null && path.endsWith("/login");
            if (!isLogin) {
                request.getHeaders().setBearerAuth(cache.currentToken());
            }
            return execution.execute(request, body);
        }
    }
}
