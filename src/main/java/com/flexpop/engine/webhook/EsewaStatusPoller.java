package com.flexpop.engine.webhook;

import com.flexpop.engine.adapter.GatewayAdapter;
import com.flexpop.engine.adapter.esewa.EsewaAdapter;
import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.TxnStatus;
import com.flexpop.engine.domain.entity.TransactionEntity;
import com.flexpop.engine.domain.repo.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pull-based status reconciliation for eSewa transactions — the backstop to the
 * browser {@code success_url} callback.
 *
 * <p>eSewa's primary settlement signal is the redirect to our signed callback,
 * but a shopper can close the tab, lose connectivity, or eSewa can take up to
 * five minutes; the status-check API exists exactly for this. Each tick polls a
 * bounded batch of {@code ROUTED}/{@code PENDING} eSewa txns and feeds terminal
 * answers through the same {@link InboundWebhookService#processSynthetic} path
 * the callback (and the Fonepay poller) use.
 *
 * <p>Mirrors {@link FonepayStatusPoller}, including deadline-based EXPIRED: eSewa
 * returns NOT_FOUND for a uuid it hasn't seen yet, which we keep non-terminal so
 * a freshly-created txn isn't failed before the shopper has paid.
 */
@Component
public class EsewaStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(EsewaStatusPoller.class);
    private static final EnumSet<TxnStatus> POLLABLE_STATUSES =
            EnumSet.of(TxnStatus.ROUTED, TxnStatus.PENDING);
    private static final Set<String> TERMINAL_RESULTS = Set.of("SETTLED", "FAILED", "EXPIRED");

    private final TransactionRepository txnRepo;
    private final EsewaAdapter adapter;
    private final InboundWebhookService inboundService;

    public EsewaStatusPoller(TransactionRepository txnRepo,
                             EsewaAdapter adapter,
                             InboundWebhookService inboundService) {
        this.txnRepo = txnRepo;
        this.adapter = adapter;
        this.inboundService = inboundService;
    }

    @Scheduled(
            fixedDelayString   = "${flexpop.gateways.esewa.poll-interval-ms:5000}",
            initialDelayString = "${flexpop.gateways.esewa.poll-initial-delay-ms:4000}"
    )
    public void tick() {
        List<TransactionEntity> batch = txnRepo.findFirst25ByGatewayAndStatusInOrderByUpdatedAtAsc(
                Gateway.ESEWA, POLLABLE_STATUSES);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("eSewa status poll: {} txns to query", batch.size());
        for (TransactionEntity txn : batch) {
            try {
                GatewayAdapter.StatusResult res = adapter.queryStatus(txn.getGatewayRef());
                inboundService.processSynthetic(txn, expireIfPastDeadline(txn, res));
            } catch (Exception e) {
                log.warn("eSewa status poll for {} failed: {}", txn.getPublicId(), e.getMessage());
            }
        }
    }

    private GatewayAdapter.StatusResult expireIfPastDeadline(TransactionEntity txn,
                                                             GatewayAdapter.StatusResult res) {
        Instant deadline = txn.getExpiresAt();
        boolean nonTerminal = !TERMINAL_RESULTS.contains(res.status());
        if (nonTerminal && deadline != null && Instant.now().isAfter(deadline)) {
            log.info("eSewa txn {} past expires_at {} with non-terminal status '{}' — marking EXPIRED",
                    txn.getPublicId(), deadline, res.status());
            return new GatewayAdapter.StatusResult(
                    res.gatewayRef(), "EXPIRED", null, "EXPIRED", "Payment window elapsed");
        }
        return res;
    }
}
