package com.flxpop.engine.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flxpop.engine.domain.Currency;
import com.flxpop.engine.domain.RefundStatus;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundResponse(

        @JsonProperty("refund_id") String refundId,
        @JsonProperty("txn_id") String txnId,
        long amount,
        Currency currency,
        RefundStatus status,
        @JsonProperty("gateway_ref") String gatewayRef,
        String reason,
        @JsonProperty("failure_code") String failureCode,
        @JsonProperty("failure_message") String failureMessage,
        @JsonProperty("settled_at") Instant settledAt,
        @JsonProperty("created_at") Instant createdAt
) { }
