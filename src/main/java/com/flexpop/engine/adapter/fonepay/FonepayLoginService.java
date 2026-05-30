package com.flexpop.engine.adapter.fonepay;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Authenticates with Fonepay via {@code POST /login} (Basic Auth + signed body)
 * and returns a Bearer token + expiry. Called by {@link FonepayTokenCache}; do
 * not call directly outside of the cache.
 *
 * <p>Uses {@code fonepayRawClient} (signing interceptor only, no auth header)
 * to avoid the circular dependency of "auth interceptor needs token cache
 * needs login service needs http client with auth interceptor".
 */
@Component
public class FonepayLoginService {

    static final String LOGIN_PATH = "/api/merchant/third-party/v2/login";

    private final RestClient http;
    private final FonepayProperties props;

    public FonepayLoginService(@Qualifier("fonepayRawClient") RestClient http,
                               FonepayProperties props) {
        this.http = http;
        this.props = props;
    }

    public LoginResult login() {
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (props.loginUsername() + ":" + props.loginPassword())
                        .getBytes(StandardCharsets.UTF_8));
        Map<String, String> body = Map.of(
                "username", props.loginUsername(),
                "password", props.loginPassword());
        try {
            TokenResponse tr = http.post()
                    .uri(LOGIN_PATH)
                    .header("Authorization", basicAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TokenResponse.class);
            if (tr == null || tr.accessToken() == null) {
                throw new IllegalStateException("Fonepay /login returned an empty or token-less body");
            }
            long ttl = tr.expiresIn() > 0 ? tr.expiresIn() : 3600L;
            return new LoginResult(tr.accessToken(), Instant.now().plusSeconds(ttl));
        } catch (RestClientException e) {
            throw new IllegalStateException("Fonepay /login failed: " + e.getMessage(), e);
        }
    }

    public record LoginResult(String accessToken, Instant expiresAt) { }

    record TokenResponse(
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("tokenType") String tokenType,
            @JsonProperty("expiresIn") long expiresIn
    ) { }
}
