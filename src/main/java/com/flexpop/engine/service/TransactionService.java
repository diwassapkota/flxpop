package com.flexpop.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexpop.engine.adapter.GatewayAdapter;
import com.flexpop.engine.adapter.GatewayAdapterRegistry;
import com.flexpop.engine.adapter.fonepay.FonepayBankCatalog;
import com.flexpop.engine.api.dto.TransactionCreateRequest;
import com.flexpop.engine.api.dto.TransactionResponse;
import com.flexpop.engine.domain.Device;
import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.Money;
import com.flexpop.engine.domain.PublicIdGenerator;
import com.flexpop.engine.domain.TxnStatus;
import com.flexpop.engine.domain.entity.CheckoutSessionEntity;
import com.flexpop.engine.domain.entity.MerchantEntity;
import com.flexpop.engine.domain.entity.RefundEntity;
import com.flexpop.engine.domain.entity.TransactionEntity;
import com.flexpop.engine.domain.entity.TransactionEventEntity;
import com.flexpop.engine.domain.repo.CheckoutSessionRepository;
import com.flexpop.engine.domain.repo.RefundRepository;
import com.flexpop.engine.domain.repo.TransactionRepository;
import com.flexpop.engine.service.exception.BadRequestException;
import com.flexpop.engine.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TransactionService {

    private final TransactionRepository txnRepo;
    private final CheckoutSessionRepository sessionRepo;
    private final RefundRepository refundRepo;
    private final GatewayAdapterRegistry adapters;
    private final TransactionEventLog eventLog;
    private final IdempotencyService idempotency;
    private final MerchantContext merchantContext;
    private final ObjectMapper mapper;
    private final FonepayBankCatalog fonepayBankCatalog;

    public TransactionService(TransactionRepository txnRepo,
                              CheckoutSessionRepository sessionRepo,
                              RefundRepository refundRepo,
                              GatewayAdapterRegistry adapters,
                              TransactionEventLog eventLog,
                              IdempotencyService idempotency,
                              MerchantContext merchantContext,
                              ObjectMapper mapper,
                              FonepayBankCatalog fonepayBankCatalog) {
        this.txnRepo = txnRepo;
        this.sessionRepo = sessionRepo;
        this.refundRepo = refundRepo;
        this.adapters = adapters;
        this.eventLog = eventLog;
        this.idempotency = idempotency;
        this.merchantContext = merchantContext;
        this.mapper = mapper;
        this.fonepayBankCatalog = fonepayBankCatalog;
    }

    public TransactionOutcome create(TransactionCreateRequest req, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required for this endpoint");
        }

        MerchantEntity merchant = merchantContext.current();

        IdempotencyService.Reservation reservation = idempotency.reserve(
                merchant.getId(), idempotencyKey, "/v1/transactions", req);

        if (reservation instanceof IdempotencyService.Reservation.Replay replay) {
            // Return the originally-issued body verbatim
            TransactionResponse cached = mapper.convertValue(replay.body(), TransactionResponse.class);
            return new TransactionOutcome(cached, true);
        }

        Long reservationId = ((IdempotencyService.Reservation.Fresh) reservation).reservationId();
        TransactionResponse body = createInner(merchant, req);

        Map<String, Object> cached = mapper.convertValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        Long txnNumericId = txnRepo.findByPublicId(body.txnId()).map(TransactionEntity::getId).orElse(null);
        idempotency.complete(reservationId, 201, cached, txnNumericId);

        return new TransactionOutcome(body, false);
    }

    @Transactional
    protected TransactionResponse createInner(MerchantEntity merchant, TransactionCreateRequest req) {
        CheckoutSessionEntity session = sessionRepo.findByPublicId(req.sessionId())
                .orElseThrow(() -> new NotFoundException("Session not found: " + req.sessionId()));

        if (!session.getMerchantId().equals(merchant.getId())) {
            throw new NotFoundException("Session not found: " + req.sessionId());
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Session expired at " + session.getExpiresAt());
        }
        if (!session.getMethods().contains(req.gateway().name())) {
            throw new BadRequestException(
                    "Gateway " + req.gateway() + " is not in this session's routed methods");
        }

        Gateway gateway = req.gateway();
        if (!adapters.supports(gateway)) {
            throw new BadRequestException("Gateway " + gateway + " has no adapter wired yet");
        }

        TransactionEntity txn = new TransactionEntity();
        txn.setPublicId(PublicIdGenerator.forTransaction(session.getCurrency()));
        txn.setMerchantId(merchant.getId());
        txn.setSessionId(session.getId());
        txn.setAmountMinor(session.getAmountMinor());
        txn.setCurrency(session.getCurrency());
        txn.setCountry(session.getCountry());
        txn.setDevice(session.getDevice());
        txn.setGateway(gateway);
        txn.setStatus(TxnStatus.CREATED);
        TransactionEntity saved = txnRepo.saveAndFlush(txn);

        eventLog.append(saved.getId(), "txn.created", TransactionEventLog.Source.ENGINE,
                Map.of(
                        "amount_minor", saved.getAmountMinor(),
                        "currency", saved.getCurrency().name(),
                        "gateway", gateway.name(),
                        "merchant_ref", session.getMerchantRef() == null ? "" : session.getMerchantRef()
                ));

        GatewayAdapter.InitiateResult initiate;
        try {
            initiate = adapters.require(gateway).initiate(new GatewayAdapter.InitiateRequest(
                    saved.getPublicId(),
                    Money.of(saved.getAmountMinor(), saved.getCurrency()),
                    saved.getCountry(),
                    saved.getDevice(),
                    session.getMerchantRef()
            ));
        } catch (RuntimeException ex) {
            saved.setStatus(TxnStatus.FAILED);
            saved.setFailureCode("ADAPTER_ERROR");
            saved.setFailureMessage(ex.getMessage());
            txnRepo.save(saved);
            eventLog.append(saved.getId(), "txn.failed", TransactionEventLog.Source.ENGINE,
                    Map.of("reason", ex.getMessage()));
            throw ex;
        }

        saved.setGatewayRef(initiate.gatewayRef());
        saved.setAppIntentUrl(initiate.appIntentUrl());
        saved.setQrPayload(initiate.qrPayload());
        saved.setExpiresAt(initiate.expiresAt());
        saved.setStatus(TxnStatus.ROUTED);
        TransactionEntity routed = txnRepo.save(saved);

        Map<String, Object> routedPayload = new HashMap<>();
        routedPayload.put("gateway_ref", initiate.gatewayRef());
        routedPayload.put("app_intent_url", initiate.appIntentUrl());
        routedPayload.put("qr_payload", initiate.qrPayload());
        routedPayload.put("expires_at", initiate.expiresAt() == null ? null : initiate.expiresAt().toString());
        eventLog.append(routed.getId(), "txn.routed", TransactionEventLog.Source.ENGINE, routedPayload);

        return toResponse(routed, eventLog.history(routed.getId()), List.of());
    }

    public TransactionResponse get(String publicId) {
        MerchantEntity merchant = merchantContext.current();
        TransactionEntity txn = txnRepo.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + publicId));
        if (!txn.getMerchantId().equals(merchant.getId())) {
            throw new NotFoundException("Transaction not found: " + publicId);
        }
        return toResponse(txn,
                eventLog.history(txn.getId()),
                refundRepo.findByTransactionIdOrderByCreatedAtAsc(txn.getId()));
    }

    private TransactionResponse toResponse(TransactionEntity t,
                                           List<TransactionEventEntity> events,
                                           List<RefundEntity> refunds) {
        List<TransactionResponse.Event> evt = events.stream()
                .map(e -> new TransactionResponse.Event(
                        e.getPublicId(),
                        e.getType(),
                        e.getSource(),
                        e.getPayload(),
                        e.getOccurredAt()))
                .toList();

        List<TransactionResponse.RefundSummary> rf = refunds.stream()
                .map(r -> new TransactionResponse.RefundSummary(
                        r.getPublicId(),
                        r.getAmountMinor(),
                        r.getStatus().name(),
                        r.getGatewayRef(),
                        r.getReason(),
                        r.getFailureCode(),
                        r.getFailureMessage(),
                        r.getSettledAt(),
                        r.getCreatedAt()))
                .toList();

        List<TransactionResponse.BankIntent> intents = buildIntents(t);

        return new TransactionResponse(
                t.getPublicId(),
                t.getAmountMinor(),
                t.getRefundedAmountMinor(),
                t.getCurrency(),
                t.getCountry(),
                t.getDevice(),
                t.getGateway(),
                t.getStatus(),
                t.getGatewayRef(),
                t.getAppIntentUrl(),
                t.getQrPayload(),
                t.getExpiresAt(),
                t.getSettledAt(),
                t.getFailureCode(),
                t.getFailureMessage(),
                intents,
                rf,
                evt,
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }

    /**
     * For mobile-device Fonepay transactions: assemble a per-bank deep-link list
     * from the cached bank catalog and the transaction's qrPayload.
     *
     * <ul>
     *   <li>Returns {@code null} (omitted from JSON) for desktop, non-Fonepay,
     *       transactions with no qrPayload, or transactions in terminal state
     *       — the widget should not be redirecting users into a bank app once
     *       the txn has settled or failed.</li>
     *   <li>Returns an empty list if the bank catalog hasn't been populated
     *       (e.g. /banks/list call failed) — the widget gracefully degrades
     *       to its QR fallback in that case.</li>
     * </ul>
     */
    private List<TransactionResponse.BankIntent> buildIntents(TransactionEntity t) {
        if (t.getDevice() != Device.MOBILE) return null;
        if (t.getGateway() != Gateway.FONEPAY) return null;
        if (t.getQrPayload() == null || t.getQrPayload().isBlank()) return null;
        if (t.getStatus().isTerminal()) return null;

        String encodedPayload = URLEncoder.encode(t.getQrPayload(), StandardCharsets.UTF_8);
        return fonepayBankCatalog.banks().stream()
                .map(b -> new TransactionResponse.BankIntent(
                        b.name(),
                        b.packageName(),
                        b.intentScheme() + "://payment/?qrPayload=" + encodedPayload))
                .toList();
    }

    public record TransactionOutcome(TransactionResponse body, boolean replayed) { }
}
