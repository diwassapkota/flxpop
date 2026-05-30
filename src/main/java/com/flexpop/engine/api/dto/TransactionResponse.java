package com.flexpop.engine.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flexpop.engine.domain.Country;
import com.flexpop.engine.domain.Currency;
import com.flexpop.engine.domain.Device;
import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.TxnStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(

        @JsonProperty("txn_id") String txnId,
        long amount,
        @JsonProperty("refunded_amount") long refundedAmount,
        Currency currency,
        Country country,
        Device device,
        Gateway gateway,
        TxnStatus status,

        @JsonProperty("gateway_ref") String gatewayRef,
        @JsonProperty("app_intent_url") String appIntentUrl,
        @JsonProperty("qr_payload") String qrPayload,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("settled_at") Instant settledAt,
        @JsonProperty("failure_code") String failureCode,
        @JsonProperty("failure_message") String failureMessage,

        List<RefundSummary> refunds,
        List<Event> events,

        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(
            @JsonProperty("event_id") String eventId,
            String type,
            String source,
            Map<String, Object> payload,
            @JsonProperty("occurred_at") Instant occurredAt
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundSummary(
            @JsonProperty("refund_id") String refundId,
            long amount,
            String status,
            @JsonProperty("gateway_ref") String gatewayRef,
            String reason,
            @JsonProperty("failure_code") String failureCode,
            @JsonProperty("failure_message") String failureMessage,
            @JsonProperty("settled_at") Instant settledAt,
            @JsonProperty("created_at") Instant createdAt
    ) { }
}
