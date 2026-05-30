package com.flexpop.engine.webhook;

import com.flexpop.engine.adapter.GatewayAdapter;
import com.flexpop.engine.adapter.fonepay.FonepayAdapter;
import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.TxnStatus;
import com.flexpop.engine.domain.entity.TransactionEntity;
import com.flexpop.engine.domain.repo.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;

/**
 * Pull-based status reconciliation for Fonepay transactions.
 *
 * <p>Fonepay's Intent API does not push HTTP webhooks to the merchant for
 * settlement — the documented options are WebSocket (real-time) or polling
 * the status endpoint (fallback). This component implements the polling
 * fallback; the WebSocket path is a separate later round.
 *
 * <p>Each tick picks a bounded batch of {@code ROUTED}/{@code PENDING} Fonepay
 * transactions, asks the adapter for their current status, and (for terminal
 * answers) hands them to {@link InboundWebhookService#processSynthetic} —
 * which runs the same state-transition / event-log / outbound-enqueue logic
 * as the HMAC-verified push path. The audit event is sourced as
 * {@link com.flexpop.engine.service.TransactionEventLog.Source#SYSTEM} so
 * the trail clearly distinguishes "pushed by gateway" from "polled".
 *
 * <p>Tests disable the auto-tick via
 * {@code flexpop.gateways.fonepay.poll-interval-ms / poll-initial-delay-ms}
 * and invoke {@link #tick()} directly when they want to exercise it.
 */
@Component
public class FonepayStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(FonepayStatusPoller.class);
    private static final EnumSet<TxnStatus> POLLABLE_STATUSES =
            EnumSet.of(TxnStatus.ROUTED, TxnStatus.PENDING);

    private final TransactionRepository txnRepo;
    private final FonepayAdapter adapter;
    private final InboundWebhookService inboundService;

    public FonepayStatusPoller(TransactionRepository txnRepo,
                               FonepayAdapter adapter,
                               InboundWebhookService inboundService) {
        this.txnRepo = txnRepo;
        this.adapter = adapter;
        this.inboundService = inboundService;
    }

    @Scheduled(
            fixedDelayString    = "${flexpop.gateways.fonepay.poll-interval-ms:5000}",
            initialDelayString  = "${flexpop.gateways.fonepay.poll-initial-delay-ms:3000}"
    )
    public void tick() {
        List<TransactionEntity> batch = txnRepo.findFirst25ByGatewayAndStatusInOrderByUpdatedAtAsc(
                Gateway.FONEPAY, POLLABLE_STATUSES);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Fonepay status poll: {} txns to query", batch.size());
        for (TransactionEntity txn : batch) {
            try {
                GatewayAdapter.StatusResult res = adapter.queryStatus(txn.getGatewayRef());
                inboundService.processSynthetic(txn, res);
            } catch (Exception e) {
                // Don't let one txn's failure stop the rest of the batch.
                log.warn("Fonepay status poll for {} failed: {}", txn.getPublicId(), e.getMessage());
            }
        }
    }
}
