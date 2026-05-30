package com.flexpop.engine.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexpop.engine.domain.entity.WebhookDeliveryEntity;
import com.flexpop.engine.domain.repo.WebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;

/**
 * Engine → merchant outbound webhook delivery.
 *
 * Polls due deliveries every 5s (configurable), claims them with FOR UPDATE
 * SKIP LOCKED so multiple workers don't double-fire, attempts HTTP POST with
 * FP-Signature header, on non-2xx schedules the next retry per Backoff.
 *
 * Treats any 2xx as success. 4xx (except 408/429) → still retried, because
 * merchants sometimes mis-configure auth and we don't want a quick dead-letter.
 */
@Component
public class OutboundWebhookWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboundWebhookWorker.class);
    private static final int BATCH_SIZE = 25;

    private final WebhookDeliveryRepository deliveryRepo;
    private final RestClient http;
    private final ObjectMapper mapper;

    public OutboundWebhookWorker(WebhookDeliveryRepository deliveryRepo,
                                 RestClient webhookDeliveryClient,
                                 ObjectMapper mapper) {
        this.deliveryRepo = deliveryRepo;
        this.http = webhookDeliveryClient;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${flexpop.webhook.poll-interval-ms:5000}")
    @Transactional
    public void tick() {
        List<WebhookDeliveryEntity> batch = deliveryRepo.claimDue(Instant.now(), BATCH_SIZE);
        if (batch.isEmpty()) return;
        log.debug("outbound webhook worker: claimed {} deliveries", batch.size());
        for (WebhookDeliveryEntity d : batch) {
            attempt(d);
        }
    }

    private void attempt(WebhookDeliveryEntity d) {
        d.setStatus("IN_FLIGHT");
        d.setAttempts(d.getAttempts() + 1);
        deliveryRepo.save(d);

        String body;
        try {
            body = mapper.writeValueAsString(d.getPayload());
        } catch (Exception e) {
            markFailedTerminal(d, "Cannot serialize payload: " + e.getMessage());
            return;
        }

        try {
            var response = http.post()
                    .uri(d.getTargetUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("FP-Signature", d.getSignature())
                    .header("FP-Event-Type", d.getEventType())
                    .header("FP-Delivery-Id", d.getPublicId())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            int code = response.getStatusCode().value();
            d.setLastResponse(code);
            d.setLastError(null);
            d.setStatus("DELIVERED");
            d.setDeliveredAt(Instant.now());
            deliveryRepo.save(d);
            log.info("delivered {} → {} ({})", d.getPublicId(), d.getTargetUrl(), code);
        } catch (RestClientResponseException ex) {
            recordFailure(d, ex.getStatusCode().value(), ex.getMessage());
        } catch (Exception ex) {
            recordFailure(d, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void recordFailure(WebhookDeliveryEntity d, Integer status, String error) {
        d.setLastResponse(status);
        d.setLastError(truncate(error, 500));

        if (d.getAttempts() >= Backoff.MAX_ATTEMPTS) {
            d.setStatus("DEAD");
            log.warn("outbound webhook DEAD after {} attempts: {} → {}",
                    d.getAttempts(), d.getPublicId(), d.getTargetUrl());
        } else {
            d.setStatus("FAILED");
            d.setNextAttemptAt(Instant.now().plus(Backoff.delayAfter(d.getAttempts())));
            log.info("outbound webhook attempt {} failed for {}: will retry at {}",
                    d.getAttempts(), d.getPublicId(), d.getNextAttemptAt());
        }
        deliveryRepo.save(d);
    }

    private void markFailedTerminal(WebhookDeliveryEntity d, String reason) {
        d.setStatus("DEAD");
        d.setLastError(truncate(reason, 500));
        deliveryRepo.save(d);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
