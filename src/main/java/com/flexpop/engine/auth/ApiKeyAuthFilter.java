package com.flexpop.engine.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexpop.engine.domain.entity.ApiKeyEntity;
import com.flexpop.engine.domain.entity.MerchantEntity;
import com.flexpop.engine.domain.repo.ApiKeyRepository;
import com.flexpop.engine.domain.repo.MerchantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * API-key auth.
 *
 * Contract:
 *   Header `Authorization: Bearer <key>` is required on every non-public route.
 *   Public routes (no auth needed):
 *     - GET  /actuator/**           — health/info, by design open in dev
 *     - POST /v1/webhooks/gateways/** — authenticated by HMAC, not API key
 *
 * Lookup:
 *   sha256(key) → api_key.key_hash → merchant. Constant-time-safe by virtue of
 *   the index hit being O(1) on a hash — no per-row string compares to time.
 *
 * On success, attaches the merchant + key id to the request as attributes; the
 * MerchantContext resolves them downstream. last_used_at is touched async.
 *
 * On failure, writes a 401 with the same error envelope GlobalExceptionHandler
 * uses so clients get a consistent shape.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final ApiKeyRepository apiKeyRepo;
    private final MerchantRepository merchantRepo;
    private final ApiKeyTouchService touchService;
    private final ObjectMapper mapper;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepo,
                            MerchantRepository merchantRepo,
                            ApiKeyTouchService touchService,
                            ObjectMapper mapper) {
        this.apiKeyRepo = apiKeyRepo;
        this.merchantRepo = merchantRepo;
        this.touchService = touchService;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        if (path.startsWith("/actuator/")) return true;
        if (path.startsWith("/v1/webhooks/gateways/")) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            writeUnauthorized(response, "missing_credentials",
                    "Authorization: Bearer <key> header is required");
            return;
        }

        String token = header.substring(BEARER.length()).trim();
        if (token.isEmpty()) {
            writeUnauthorized(response, "missing_credentials", "Bearer token is empty");
            return;
        }

        Optional<ApiKeyEntity> keyOpt = apiKeyRepo.findByKeyHashAndRevokedAtIsNull(sha256(token));
        if (keyOpt.isEmpty()) {
            writeUnauthorized(response, "invalid_credentials", "API key invalid or revoked");
            return;
        }

        ApiKeyEntity key = keyOpt.get();
        MerchantEntity merchant = merchantRepo.findById(key.getMerchantId()).orElse(null);
        if (merchant == null || !"ACTIVE".equals(merchant.getStatus())) {
            writeUnauthorized(response, "invalid_credentials", "Merchant inactive or missing");
            return;
        }

        request.setAttribute(AuthAttributes.MERCHANT,     merchant);
        request.setAttribute(AuthAttributes.API_KEY_ID,   key.getId());
        request.setAttribute(AuthAttributes.API_KEY_KIND, key.getKind());

        touchService.touchAsync(key.getId());

        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String type, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", type);
        error.put("message", message);
        error.put("request_id", "rq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        mapper.writeValue(response.getOutputStream(), Map.of("error", error));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Tiny helper bean so we can mark touch as @Async without putting @Async on
     * the filter itself (Spring won't proxy a filter).
     */
    @Component
    public static class ApiKeyTouchService {
        private final ApiKeyRepository repo;

        @Autowired
        public ApiKeyTouchService(ApiKeyRepository repo) { this.repo = repo; }

        @Async
        @org.springframework.transaction.annotation.Transactional
        public void touchAsync(Long apiKeyId) {
            try {
                repo.touchLastUsed(apiKeyId, Instant.now());
            } catch (Exception ex) {
                LoggerFactory.getLogger(ApiKeyTouchService.class)
                        .warn("Failed to touch last_used_at for api_key id={}: {}", apiKeyId, ex.getMessage());
            }
        }
    }
}
