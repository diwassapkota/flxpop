package com.flxpop.engine.webhook;

import com.flxpop.engine.adapter.GatewayAdapter;
import com.flxpop.engine.adapter.fonepay.FonepayAdapter;
import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.domain.TxnStatus;
import com.flxpop.engine.domain.entity.TransactionEntity;
import com.flxpop.engine.domain.repo.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
 * {@link com.flxpop.engine.service.TransactionEventLog.Source#SYSTEM} so
 * the trail clearly distinguishes "pushed by gateway" from "polled".
 *
 * <p>Tests disable the auto-tick via
 * {@code flxpop.gateways.fonepay.poll-interval-ms / poll-initial-delay-ms}
 * and invoke {@link #tick()} directly when they want to exercise it.
 */
@Component
public class FonepayStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(FonepayStatusPoller.class);
    private static final EnumSet<TxnStatus> POLLABLE_STATUSES =
            EnumSet.of(TxnStatus.ROUTED, TxnStatus.PENDING);
    /** StatusResult.status() values that are already a final answer from the gateway. */
    private static final Set<String> TERMINAL_RESULTS = Set.of("SETTLED", "FAILED", "EXPIRED");

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
            fixedDelayString    = "${flxpop.gateways.fonepay.poll-interval-ms:5000}",
            initialDelayString  = "${flxpop.gateways.fonepay.poll-initial-delay-ms:3000}"
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
                inboundService.processSynthetic(txn, expireIfPastDeadline(txn, res));
            } catch (Exception e) {
                // Don't let one txn's failure stop the rest of the batch.
                log.warn("Fonepay status poll for {} failed: {}", txn.getPublicId(), e.getMessage());
            }
        }
    }

    /**
     * Deadline-based expiry. Fonepay returns a NON-terminal status
     * ({@code "timeout"} / {@code "Data not found."} / pending) for the entire
     * life of an unscanned QR — including a brand-new one queried seconds after
     * creation — so the gateway string can never be trusted to mean "expired".
     * Instead we expire off the engine's own authoritative deadline: once a txn
     * passes its {@code expires_at} (the Intent QR's TTL) and the gateway still
     * hasn't reported a terminal result, we synthesise an {@code EXPIRED} result
     * so it stops being re-polled forever. A gateway answer that is already
     * terminal (settled/failed) is always honoured as-is.
     */
    private GatewayAdapter.StatusResult expireIfPastDeadline(TransactionEntity txn,
                                                             GatewayAdapter.StatusResult res) {
        Instant deadline = txn.getExpiresAt();
        boolean nonTerminal = !TERMINAL_RESULTS.contains(res.status());
        if (nonTerminal && deadline != null && Instant.now().isAfter(deadline)) {
            log.info("Fonepay txn {} past expires_at {} with non-terminal gateway status '{}' — marking EXPIRED",
                    txn.getPublicId(), deadline, res.status());
            return new GatewayAdapter.StatusResult(
                    res.gatewayRef(), "EXPIRED", null, "EXPIRED", "Payment window elapsed");
        }
        return res;
    }
}
