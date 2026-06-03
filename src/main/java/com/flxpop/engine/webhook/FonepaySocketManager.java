package com.flxpop.engine.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flxpop.engine.adapter.GatewayAdapter;
import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.domain.TxnStatus;
import com.flxpop.engine.domain.entity.TransactionEntity;
import com.flxpop.engine.domain.repo.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side listener for Fonepay's real-time payment socket.
 *
 * <p>Fonepay's Intent API has no merchant webhook; the documented confirmation
 * channels are the QR WebSocket or the status API. For this merchant the status
 * API returns {@code 409 "Unauthorized request"}, and the in-browser socket
 * can't survive a mobile tab reload (the bank-app round trip evicts the page).
 * So the engine opens the socket ITSELF for each pending Fonepay transaction and
 * settles it from the {@code paymentSuccess} frame. Because the result is then
 * recorded server-side, the checkout recovers it on return even after a reload
 * (it just re-reads the transaction).
 *
 * <p>A periodic sweep ensures exactly one live connection per pending txn:
 * it opens missing ones, lets closed/errored ones be reopened next tick
 * (reconnect), re-attaches after an engine restart (the URL is persisted on the
 * transaction row), and expires txns past their deadline so we don't listen
 * forever. The actual state transition reuses {@link InboundWebhookService#processSynthetic}
 * — the same path the status poller used — so audit/event/outbound logic is identical.
 */
@Component
public class FonepaySocketManager {

    private static final Logger log = LoggerFactory.getLogger(FonepaySocketManager.class);
    private static final EnumSet<TxnStatus> PENDING = EnumSet.of(TxnStatus.ROUTED, TxnStatus.PENDING);

    private final TransactionRepository txnRepo;
    private final InboundWebhookService inbound;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    /** Public IDs we currently hold (or are opening) a socket for. */
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public FonepaySocketManager(TransactionRepository txnRepo,
                                InboundWebhookService inbound,
                                @Value("${flxpop.gateways.fonepay.socket-enabled:true}") boolean enabled) {
        this.txnRepo = txnRepo;
        this.inbound = inbound;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString   = "${flxpop.gateways.fonepay.socket-sweep-ms:3000}",
            initialDelayString = "${flxpop.gateways.fonepay.socket-initial-delay-ms:3000}"
    )
    public void sweep() {
        if (!enabled) return;
        List<TransactionEntity> batch =
                txnRepo.findFirst25ByGatewayAndStatusInOrderByUpdatedAtAsc(Gateway.FONEPAY, PENDING);
        for (TransactionEntity t : batch) {
            Instant deadline = t.getExpiresAt();
            if (deadline != null && Instant.now().isAfter(deadline)) {
                // Past the QR TTL with no terminal frame — stop listening and expire it.
                markTerminal(t.getPublicId(), "EXPIRED");
                active.remove(t.getPublicId());
                continue;
            }
            String url = t.getWebsocketUrl();
            if (url == null || url.isBlank()) continue;
            if (active.add(t.getPublicId())) {   // add() == true → not already connected
                open(t.getPublicId(), url);
            }
        }
    }

    private void open(String publicId, String url) {
        try {
            log.info("Fonepay socket: connecting for {}", publicId);
            http.newWebSocketBuilder()
                .buildAsync(URI.create(url), new Listener(publicId))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.warn("Fonepay socket connect failed for {}: {}", publicId, err.getMessage());
                        active.remove(publicId);   // let the next sweep retry
                    }
                });
        } catch (RuntimeException e) {
            log.warn("Fonepay socket: bad URL for {}: {}", publicId, e.getMessage());
            active.remove(publicId);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final String publicId;
        private final StringBuilder buf = new StringBuilder();

        Listener(String publicId) { this.publicId = publicId; }

        @Override public void onOpen(WebSocket ws) { ws.request(1); }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                String outcome = parse(msg);   // "SETTLED" | "FAILED" | null
                if (outcome != null) {
                    log.info("Fonepay socket: {} -> {}", publicId, outcome);
                    markTerminal(publicId, outcome);
                    active.remove(publicId);
                    try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"); } catch (RuntimeException ignore) { /* */ }
                }
            }
            ws.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            active.remove(publicId);   // sweep reconnects if the txn is still pending
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) {
            log.warn("Fonepay socket error for {}: {}", publicId, error.getMessage());
            active.remove(publicId);
        }
    }

    /**
     * Parse a Fonepay socket frame into a terminal status, or null if it isn't a
     * payment result. Field names verified against the live production socket:
     * the outer frame carries a {@code transactionStatus} JSON *string* whose
     * {@code paymentSuccess} boolean is the result (the verification frame has
     * {@code qrVerified} and no {@code paymentSuccess} — ignored here).
     */
    String parse(String raw) {
        try {
            JsonNode outer = mapper.readTree(raw);
            JsonNode ts = outer.get("transactionStatus");
            if (ts == null) return null;
            JsonNode inner = ts.isTextual() ? mapper.readTree(ts.asText()) : ts;
            if (inner != null && inner.has("paymentSuccess")) {
                return inner.get("paymentSuccess").asBoolean() ? "SETTLED" : "FAILED";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void markTerminal(String publicId, String status) {
        try {
            txnRepo.findByPublicId(publicId).ifPresent(t -> {
                if (t.getStatus().isTerminal()) return;
                GatewayAdapter.StatusResult res = switch (status) {
                    case "SETTLED" -> new GatewayAdapter.StatusResult(t.getGatewayRef(), "SETTLED", Instant.now(), null, null);
                    case "EXPIRED" -> new GatewayAdapter.StatusResult(t.getGatewayRef(), "EXPIRED", null, "EXPIRED", "Payment window elapsed");
                    default        -> new GatewayAdapter.StatusResult(t.getGatewayRef(), "FAILED", null, "GATEWAY_FAILED", "Payment declined");
                };
                inbound.processSynthetic(t, res);
            });
        } catch (Exception e) {
            log.warn("Fonepay socket: failed to settle {}: {}", publicId, e.getMessage());
        }
    }
}
