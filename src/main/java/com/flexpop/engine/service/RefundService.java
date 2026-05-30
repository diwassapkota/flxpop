package com.flexpop.engine.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexpop.engine.adapter.GatewayAdapter;
import com.flexpop.engine.adapter.GatewayAdapterRegistry;
import com.flexpop.engine.api.dto.RefundCreateRequest;
import com.flexpop.engine.api.dto.RefundResponse;
import com.flexpop.engine.domain.Money;
import com.flexpop.engine.domain.PublicIdGenerator;
import com.flexpop.engine.domain.RefundStatus;
import com.flexpop.engine.domain.TxnStatus;
import com.flexpop.engine.domain.entity.MerchantEntity;
import com.flexpop.engine.domain.entity.RefundEntity;
import com.flexpop.engine.domain.entity.TransactionEntity;
import com.flexpop.engine.domain.repo.RefundRepository;
import com.flexpop.engine.domain.repo.TransactionRepository;
import com.flexpop.engine.service.exception.BadRequestException;
import com.flexpop.engine.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class RefundService {

    private final RefundRepository refundRepo;
    private final TransactionRepository txnRepo;
    private final GatewayAdapterRegistry adapters;
    private final TransactionEventLog eventLog;
    private final IdempotencyService idempotency;
    private final MerchantContext merchantContext;
    private final ObjectMapper mapper;

    public RefundService(RefundRepository refundRepo,
                         TransactionRepository txnRepo,
                         GatewayAdapterRegistry adapters,
                         TransactionEventLog eventLog,
                         IdempotencyService idempotency,
                         MerchantContext merchantContext,
                         ObjectMapper mapper) {
        this.refundRepo = refundRepo;
        this.txnRepo = txnRepo;
        this.adapters = adapters;
        this.eventLog = eventLog;
        this.idempotency = idempotency;
        this.merchantContext = merchantContext;
        this.mapper = mapper;
    }

    @Transactional
    public RefundOutcome create(String txnPublicId, RefundCreateRequest req, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required for this endpoint");
        }

        MerchantEntity merchant = merchantContext.current();
        String requestPath = "/v1/transactions/" + txnPublicId + "/refunds";

        IdempotencyService.Reservation reservation = idempotency.reserve(
                merchant.getId(), idempotencyKey, requestPath, req);

        if (reservation instanceof IdempotencyService.Reservation.Replay replay) {
            RefundResponse cached = mapper.convertValue(replay.body(), RefundResponse.class);
            return new RefundOutcome(cached, true);
        }

        Long reservationId = ((IdempotencyService.Reservation.Fresh) reservation).reservationId();
        RefundResponse body = createInner(merchant, txnPublicId, req);

        Map<String, Object> cached = mapper.convertValue(body, new TypeReference<Map<String, Object>>() { });
        idempotency.complete(reservationId, 202, cached, null);

        return new RefundOutcome(body, false);
    }

    private RefundResponse createInner(MerchantEntity merchant, String txnPublicId, RefundCreateRequest req) {
        TransactionEntity txn = txnRepo.findForUpdateByPublicId(txnPublicId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + txnPublicId));

        if (!txn.getMerchantId().equals(merchant.getId())) {
            throw new NotFoundException("Transaction not found: " + txnPublicId);
        }
        if (txn.getStatus() != TxnStatus.SETTLED) {
            throw new BadRequestException(
                    "Only SETTLED transactions can be refunded; current status: " + txn.getStatus());
        }
        long remaining = txn.getRefundableAmountMinor();
        if (req.amount() > remaining) {
            throw new BadRequestException(
                    "Refund amount " + req.amount() + " exceeds remaining refundable amount " + remaining);
        }

        GatewayAdapter adapter = adapters.require(txn.getGateway());

        RefundEntity refund = new RefundEntity();
        refund.setPublicId(PublicIdGenerator.forRefund(txn.getCurrency()));
        refund.setTransactionId(txn.getId());
        refund.setMerchantId(merchant.getId());
        refund.setAmountMinor(req.amount());
        refund.setCurrency(txn.getCurrency());
        refund.setGateway(txn.getGateway());
        refund.setReason(req.reason());
        refund.setStatus(RefundStatus.PENDING);
        RefundEntity saved = refundRepo.saveAndFlush(refund);

        Map<String, Object> createdEvent = new HashMap<>();
        createdEvent.put("refund_id", saved.getPublicId());
        createdEvent.put("amount_minor", saved.getAmountMinor());
        createdEvent.put("currency", saved.getCurrency().name());
        createdEvent.put("reason", saved.getReason() == null ? "" : saved.getReason());
        eventLog.append(txn.getId(), "refund.created", TransactionEventLog.Source.ENGINE, createdEvent);

        GatewayAdapter.RefundResult result;
        try {
            result = adapter.refund(new GatewayAdapter.RefundRequest(
                    saved.getPublicId(),
                    txn.getGatewayRef(),
                    Money.of(saved.getAmountMinor(), saved.getCurrency()),
                    saved.getReason()));
        } catch (RuntimeException ex) {
            saved.setStatus(RefundStatus.FAILED);
            saved.setFailureCode("ADAPTER_ERROR");
            saved.setFailureMessage(ex.getMessage());
            refundRepo.save(saved);
            eventLog.append(txn.getId(), "refund.failed", TransactionEventLog.Source.ENGINE,
                    Map.of("refund_id", saved.getPublicId(), "reason", ex.getMessage()));
            throw ex;
        }

        saved.setGatewayRef(result.gatewayRef());
        RefundEntity routed = refundRepo.save(saved);

        eventLog.append(txn.getId(), "refund.routed", TransactionEventLog.Source.ENGINE,
                Map.of(
                        "refund_id", routed.getPublicId(),
                        "gateway_ref", result.gatewayRef()
                ));

        return toResponse(routed, txn);
    }

    private RefundResponse toResponse(RefundEntity r, TransactionEntity t) {
        return new RefundResponse(
                r.getPublicId(),
                t.getPublicId(),
                r.getAmountMinor(),
                r.getCurrency(),
                r.getStatus(),
                r.getGatewayRef(),
                r.getReason(),
                r.getFailureCode(),
                r.getFailureMessage(),
                r.getSettledAt(),
                r.getCreatedAt()
        );
    }

    public record RefundOutcome(RefundResponse body, boolean replayed) { }
}
