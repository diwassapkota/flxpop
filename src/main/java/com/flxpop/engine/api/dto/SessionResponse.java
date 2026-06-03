package com.flxpop.engine.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flxpop.engine.domain.Country;
import com.flxpop.engine.domain.Currency;
import com.flxpop.engine.domain.Device;

import java.time.Instant;
import java.util.List;

public record SessionResponse(
        @JsonProperty("session_id") String sessionId,
        long amount,
        Currency currency,
        Country country,
        Device device,
        List<MethodSummary> methods,
        @JsonProperty("expires_at") Instant expiresAt
) {
    public record MethodSummary(String gateway, @JsonProperty("display_name") String displayName) { }
}
