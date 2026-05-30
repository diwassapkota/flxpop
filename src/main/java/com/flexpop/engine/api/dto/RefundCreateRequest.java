package com.flexpop.engine.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RefundCreateRequest(

        @NotNull
        @Positive
        Long amount,

        @Size(max = 500)
        String reason
) { }
