package com.flexpop.engine.adapter.fonepay;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

/**
 * Cached snapshot of {@code GET /banks/list}. The list of banks (with each
 * bank's Android package and intent scheme) is needed to assemble per-bank
 * deep-link URIs on the mobile flow.
 *
 * <p>Bank schemes change infrequently — Fonepay onboards a new bank order of
 * weeks. A 12h refresh cycle is generous. On refresh failure we keep the
 * existing snapshot (stale-OK) and log a warning; an empty catalog gracefully
 * degrades the widget to its QR fallback.
 */
@Component
public class FonepayBankCatalog {

    private static final Logger log = LoggerFactory.getLogger(FonepayBankCatalog.class);
    static final String BANKS_PATH = "/api/merchant/third-party/v2/banks/list";

    private final RestClient http;
    private volatile List<Bank> cached = List.of();
    private volatile Instant lastSuccessfulRefresh;

    public FonepayBankCatalog(@Qualifier("fonepayAuthedClient") RestClient http) {
        this.http = http;
    }

    public List<Bank> banks() {
        if (lastSuccessfulRefresh == null) {
            // No successful fetch yet — try once synchronously so the first txn
            // after boot doesn't get an empty intent list while the scheduler
            // is still asleep. Subsequent calls hit the cache.
            refresh();
        }
        return cached;
    }

    @Scheduled(fixedRateString = "PT12H", initialDelayString = "PT12H")
    public void refresh() {
        try {
            BankListResponse res = http.get()
                    .uri(BANKS_PATH)
                    .retrieve()
                    .body(BankListResponse.class);
            if (res != null && res.banks() != null) {
                cached = res.banks().stream()
                        .map(b -> new Bank(b.name(), b.packageName(), b.intentScheme()))
                        .toList();
                lastSuccessfulRefresh = Instant.now();
                log.info("Fonepay bank catalog refreshed: {} banks", cached.size());
            }
        } catch (Exception e) {
            log.warn("Fonepay bank catalog refresh failed (keeping previous list, {} entries): {}",
                    cached.size(), e.getMessage());
        }
    }

    public record Bank(String name, String packageName, String intentScheme) { }

    record BankListResponse(List<RawBank> banks) { }
    record RawBank(
            @JsonProperty("name") String name,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("intentScheme") String intentScheme
    ) { }
}
