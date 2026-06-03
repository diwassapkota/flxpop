package com.flxpop.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flxpop.engine.domain.entity.IdempotencyRecordEntity;
import com.flxpop.engine.domain.repo.IdempotencyRecordRepository;
import com.flxpop.engine.service.exception.IdempotencyConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Idempotency for POST writes.
 *
 * Contract:
 *   - On first call: insert a row with (merchant_id, idempotency_key); reservation succeeds.
 *   - On replay with the SAME request body: return the cached response.
 *   - On replay with a DIFFERENT request body: 409 — the spec calls this out
 *     because silently returning the wrong answer would be a worse footgun.
 *   - If the original call is in flight (no response yet): treat as 409 to keep
 *     the contract simple. Real high-concurrency tuning is a later concern.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repo;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyRecordRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public Reservation reserve(Long merchantId, String key, String requestPath, Object requestBody) {
        String requestHash = sha256(serialize(requestBody));

        Optional<IdempotencyRecordEntity> existing =
                repo.findByMerchantIdAndIdempotencyKey(merchantId, key);

        if (existing.isPresent()) {
            IdempotencyRecordEntity row = existing.get();
            if (!row.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key reused with a different request body");
            }
            if (row.getCompletedAt() == null) {
                throw new IdempotencyConflictException(
                        "Original request with this Idempotency-Key is still in flight");
            }
            return Reservation.replay(row.getResponseStatus(), row.getResponseBody());
        }

        IdempotencyRecordEntity fresh = new IdempotencyRecordEntity();
        fresh.setMerchantId(merchantId);
        fresh.setIdempotencyKey(key);
        fresh.setRequestPath(requestPath);
        fresh.setRequestHash(requestHash);
        try {
            IdempotencyRecordEntity saved = persistInNewTx(fresh);
            return Reservation.fresh(saved.getId());
        } catch (DataIntegrityViolationException race) {
            // Lost the race: another thread inserted between our SELECT and INSERT.
            // Treat as a duplicate; the caller can retry to read the cached result.
            throw new IdempotencyConflictException(
                    "Concurrent request with the same Idempotency-Key");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecordEntity persistInNewTx(IdempotencyRecordEntity e) {
        return repo.saveAndFlush(e);
    }

    @Transactional
    public void complete(Long reservationId, int httpStatus, Map<String, Object> body, Long txnId) {
        IdempotencyRecordEntity row = repo.findById(reservationId).orElseThrow();
        row.setResponseStatus(httpStatus);
        row.setResponseBody(body);
        row.setTransactionId(txnId);
        row.setCompletedAt(Instant.now());
        repo.save(row);
    }

    private String serialize(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize request body for hashing", e);
        }
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

    public sealed interface Reservation {
        static Reservation fresh(Long id) { return new Fresh(id); }
        static Reservation replay(Integer status, Map<String, Object> body) { return new Replay(status, body); }

        record Fresh(Long reservationId) implements Reservation { }
        record Replay(Integer status, Map<String, Object> body) implements Reservation { }
    }
}
