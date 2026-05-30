package com.flexpop.engine.api.dto;

import com.flexpop.engine.domain.Country;
import com.flexpop.engine.domain.Currency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SessionCreateRequest(

        @NotNull
        @Positive
        Long amount,

        @NotNull
        Currency currency,

        Country country,

        @Size(max = 120)
        String reference
) { }
