package com.flexpop.engine.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexpop.engine.adapter.GatewayAdapter;
import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.PublicIdGenerator;
import com.flexpop.engine.domain.RefundStatus;
import com.flexpop.engine.domain.TxnStatus;
import com.flexpop.engine.domain.entity.InboundWebhookEntity;
import com.flexpop.engine.domain.entity.MerchantEntity;
import com.flexpop.engine.domain.entity.RefundEntity;
import com.flexpop.engine.domain.entity.TransactionEntity;
import com.flexpop.engine.domain.entity.WebhookDeliveryEntity;
import com.flexpop.engine.domain.repo.InboundWebhookRepository;
import com.flexpop.engine.domain.repo.MerchantRepository;
import com.flexpop.engine.domain.repo.RefundRepository;
import com.flexpop.engine.domain.repo.TransactionRepository;
import com.flexpop.engine.domain.repo.WebhookDeliveryRepository;
import com.flexpop.engine.service.TransactionEventLog;
import com.flexpop.engine.service.exception.BadRequestException;
import com.flexpop.engine.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Inbound: gateway → engine.
 *
 * Dispatch by event type:
 *   payment.* → resolve transaction by gateway_ref, transition its status.
 *   refund.*  → resolve refund by its own gateway_ref, transition refund status,
 *               update parent txn.refunded_amount_minor, flip txn.status to
 *               REFUNDED if fully refunded.
 *
 * Both paths share: HMAC verify, dedup on (gateway, gateway_event_id),
 * append-only event log on the parent txn, outbound enqueue to the merchant.
 */
@Service
public class InboundWebhookService {

    private static final Logger log = LoggerFactory.getLogger(InboundWebhookService.class);

    private final InboundWebhookRepository inboundRepo;
    private final TransactionRepository txnRepo;
    private final RefundRepository refundRepo;
    private final MerchantRepository merchantRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final TransactionEventLog eventLog;
    private final HmacSigner hmac;
    private final GatewaySecrets secrets;
    private final ObjectMapper mapper;

    public InboundWebhookService(InboundWebhookRepository inboundRepo,
                                 TransactionRepository txnRepo,
                                 RefundRepository refundRepo,
                                 MerchantRepository merchantRepo,
                                 WebhookDeliveryRepository deliveryRepo,
                                 TransactionEventLog eventLog,
                                 HmacSigner hmac,
                                 GatewaySecrets secrets,
                                 ObjectMapper mapper) {
        this.inboundRepo = inboundRepo;
        this.txnRepo = txnRepo;
        this.refundRepo = refundRepo;
        this.merchantRepo = merchantRepo;
        this.deliveryRepo = deliveryRepo;
        this.eventLog = eventLog;
        this.hmac = hmac;
        this.secrets = secrets;
        this.mapper = mapper;
    }

    @Transactional
    public InboundResult handle(Gateway gateway, String rawBody, String signature) {
        if (!hmac.verify(rawBody, secrets.secretFor(gateway), signature)) {
            log.warn("inbound webhook: bad signature for {}", gateway);
            return InboundResult.BAD_SIGNATURE;
        }

        Map<String, Object> body = parse(rawBody);
        String gatewayEventId = stringField(body, "event_id");
        if (gatewayEventId == null) {
            throw new BadRequestException("Missing event_id in webhook body");
        }

        Optional<InboundWebhookEntity> existing =
                inboundRepo.findByGatewayAndGatewayEventId(gateway, gatewayEventId);
        if (existing.isPresent()) {
            log.info("inbound webhook: duplicate {} {} — acking", gateway, gatewayEventId);
            return InboundResult.DUPLICATE;
        }

        String type = stringField(body, "type");
        if (type == null) {
            throw new BadRequestException("Missing type in webhook body");
        }

        InboundWebhookEntity inbound = new InboundWebhookEntity();
        inbound.setGateway(gateway);
        inbound.setGatewayEventId(gatewayEventId);
        inbound.setRawBody(rawBody);
        inbound.setSignature(signature);
        inbound.setType(type);
        InboundWebhookEntity savedInbound = inboundRepo.save(inbound);

        if (type.startsWith("payment.")) {
            handlePaymentEvent(gateway, type, body, savedInbound);
        } else if (type.startsWith("refund.")) {
            handleRefundEvent(gateway, type, body, savedInbound);
        } else {
            throw new BadRequestException("Unknown webhook type: " + type);
        }

        return InboundResult.PROCESSED;
    }

    private void handlePaymentEvent(Gateway gateway, String type, Map<String, Object> body,
                                    InboundWebhookEntity savedInbound) {
        String gatewayRef = stringField(body, "gateway_ref");
        TransactionEntity txn = txnRepo.findByGatewayAndGatewayRef(gateway, gatewayRef)
                .orElseThrow(() -> new NotFoundException(
                        "No transaction for gateway_ref=" + gatewayRef));

        TxnStatus newStatus = mapPaymentType(type);
        applyTxnStatus(txn, newStatus, body);
        txnRepo.save(txn);

        savedInbound.setTransactionId(txn.getId());
        savedInbound.setProcessedAt(Instant.now());

        eventLog.append(txn.getId(), type, TransactionEventLog.Source.GATEWAY, body);
        enqueueOutboundForMerchant(txn, type, body);
    }

    /**
     * Synthetic-webhook path used by {@code FonepayStatusPoller} (and any future
     * pull-based gateway integration). Same state-transition + event-append +
     * outbound-enqueue logic as the HMAC-verified path, but tagged
     * {@link TransactionEventLog.Source#SYSTEM} so the audit trail shows
     * "polled" not "pushed by gateway".
     *
     * <p>No-op if {@link GatewayAdapter.StatusResult#status()} is non-terminal —
     * the poller keeps the txn in ROUTED/PENDING and tries again next tick.
     */
    @Transactional
    public void processSynthetic(TransactionEntity txn, GatewayAdapter.StatusResult statusResult) {
        TxnStatus newStatus;
        String eventType;
        switch (statusResult.status()) {
            case "SETTLED" -> { newStatus = TxnStatus.SETTLED; eventType = "payment.settled"; }
            case "FAILED"  -> { newStatus = TxnStatus.FAILED;  eventType = "payment.failed";  }
            case "EXPIRED" -> { newStatus = TxnStatus.EXPIRED; eventType = "payment.expired"; }
            default -> {
                // PENDING / unknown — caller will retry on the next tick.
                return;
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("type",        eventType);
        body.put("source",      "polled");
        body.put("txn_id",      txn.getPublicId());
        body.put("gateway_ref", txn.getGatewayRef());
        if (statusResult.failureCode() != null)    body.put("failure_code",    statusResult.failureCode());
        if (statusResult.failureMessage() != null) body.put("failure_message", statusResult.failureMessage());

        if (txn.getStatus().isTerminal() && newStatus != txn.getStatus()) {
            log.warn("ignoring late synthetic transition for {}: {} → {}",
                    txn.getPublicId(), txn.getStatus(), newStatus);
            return;
        }
        applyTxnStatus(txn, newStatus, body);
        txnRepo.save(txn);

        eventLog.append(txn.getId(), eventType, TransactionEventLog.Source.SYSTEM, body);
        enqueueOutboundForMerchant(txn, eventType, body);
    }

    private void handleRefundEvent(Gateway gateway, String type, Map<String, Object> body,
                                   InboundWebhookEntity savedInbound) {
        String refundGatewayRef = stringField(body, "gateway_ref");
        RefundEntity refund = refundRepo.findByGatewayAndGatewayRef(gateway, refundGatewayRef)
                .orElseThrow(() -> new NotFoundException(
                        "No refund for gateway_ref=" + refundGatewayRef));

        if (refund.getStatus() == RefundStatus.SETTLED || refund.getStatus() == RefundStatus.FAILED) {
            log.warn("ignoring late state transition for refund {}: already {}",
                    refund.getPublicId(), refund.getStatus());
            return;
        }

        TransactionEntity txn = txnRepo.findById(refund.getTransactionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Refund " + refund.getPublicId() + " references missing txn"));

        switch (type) {
            case "refund.settled" -> {
                refund.setStatus(RefundStatus.SETTLED);
                refund.setSettledAt(Instant.now());
                refundRepo.save(refund);

                txn.setRefundedAmountMinor(txn.getRefundedAmountMinor() + refund.getAmountMinor());
                if (txn.getRefundedAmountMinor() >= txn.getAmountMinor()) {
                    txn.setStatus(TxnStatus.REFUNDED);
                }
                txnRepo.save(txn);
            }
            case "refund.failed" -> {
                refund.setStatus(RefundStatus.FAILED);
                refund.setFailureCode(stringField(body, "failure_code"));
                refund.setFailureMessage(stringField(body, "failure_message"));
                refundRepo.save(refund);
            }
            default -> throw new BadRequestException("Unknown refund webhook type: " + type);
        }

        savedInbound.setTransactionId(txn.getId());
        savedInbound.setProcessedAt(Instant.now());

        eventLog.append(txn.getId(), type, TransactionEventLog.Source.GATEWAY, body);
        enqueueOutboundForMerchant(txn, type, body);
    }

    private TxnStatus mapPaymentType(String type) {
        return switch (type) {
            case "payment.settled" -> TxnStatus.SETTLED;
            case "payment.failed"  -> TxnStatus.FAILED;
            case "payment.expired" -> TxnStatus.EXPIRED;
            case "payment.routed"  -> TxnStatus.ROUTED;
            default -> throw new BadRequestException("Unknown payment webhook type: " + type);
        };
    }

    private void applyTxnStatus(TransactionEntity txn, TxnStatus newStatus, Map<String, Object> body) {
        if (txn.getStatus().isTerminal() && newStatus != txn.getStatus()) {
            log.warn("ignoring late state transition for {}: {} → {}",
                    txn.getPublicId(), txn.getStatus(), newStatus);
            return;
        }
        txn.setStatus(newStatus);
        if (newStatus == TxnStatus.SETTLED) {
            txn.setSettledAt(Instant.now());
        } else if (newStatus == TxnStatus.FAILED) {
            txn.setFailureCode(stringField(body, "failure_code"));
            txn.setFailureMessage(stringField(body, "failure_message"));
        }
    }

    private void enqueueOutboundForMerchant(TransactionEntity txn, String type, Map<String, Object> gatewayPayload) {
        MerchantEntity merchant = merchantRepo.findById(txn.getMerchantId())
                .orElseThrow(() -> new IllegalStateException("Merchant vanished: " + txn.getMerchantId()));
        if (merchant.getWebhookUrl() == null || merchant.getWebhookSecret() == null) {
            log.info("merchant {} has no webhook url/secret — skipping outbound", merchant.getPublicId());
            return;
        }

        Map<String, Object> payload = Map.of(
                "event_id", PublicIdGenerator.forEvent(),
                "type", type,
                "txn_id", txn.getPublicId(),
                "status", txn.getStatus().name(),
                "amount_minor", txn.getAmountMinor(),
                "currency", txn.getCurrency().name(),
                "gateway", txn.getGateway().name(),
                "occurred_at", Instant.now().toString()
        );

        String serialized;
        try {
            serialized = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbound payload", e);
        }

        WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
        delivery.setPublicId(PublicIdGenerator.forWebhookDelivery());
        delivery.setMerchantId(merchant.getId());
        delivery.setTransactionId(txn.getId());
        delivery.setEventType(type);
        delivery.setPayload(payload);
        delivery.setTargetUrl(merchant.getWebhookUrl());
        delivery.setSignature(hmac.sign(serialized, merchant.getWebhookSecret()));
        delivery.setStatus("PENDING");
        delivery.setNextAttemptAt(Instant.now());
        deliveryRepo.save(delivery);
    }

    private Map<String, Object> parse(String rawBody) {
        try {
            return mapper.readValue(rawBody, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new BadRequestException("Webhook body is not valid JSON: " + e.getMessage());
        }
    }

    private static String stringField(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public enum InboundResult { PROCESSED, DUPLICATE, BAD_SIGNATURE }
}
