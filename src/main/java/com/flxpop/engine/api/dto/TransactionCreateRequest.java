package com.flxpop.engine.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flxpop.engine.domain.Gateway;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TransactionCreateRequest(

        @JsonProperty("session_id")
        @NotBlank
        String sessionId,

        @NotNull
        Gateway gateway
) { }
