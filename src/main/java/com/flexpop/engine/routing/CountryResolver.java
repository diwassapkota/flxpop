package com.flexpop.engine.routing;

import com.flexpop.engine.domain.Country;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the country for a checkout session.
 *
 * Resolution order (first wins):
 *   1. Explicit `country` field in the request body (merchant-known billing country).
 *   2. `X-FlexPop-Country` header (CDN / reverse-proxy geo header).
 *   3. Derived from the request currency.
 *
 * Real geo-IP lookup (MaxMind, etc.) lands here in a later round — kept off the
 * critical path now so tests don't need a GeoLite DB checked in.
 */
@Component
public class CountryResolver {

    public Country resolve(HttpServletRequest request, Country explicit, String currency) {
        if (explicit != null) {
            return explicit;
        }
        String header = request.getHeader("X-FlexPop-Country");
        if (header != null && !header.isBlank()) {
            try {
                return Country.valueOf(header.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return fromCurrency(currency).orElseThrow(() ->
                new IllegalArgumentException("Unable to resolve country; pass `country` explicitly"));
    }

    private Optional<Country> fromCurrency(String ccy) {
        if (ccy == null) return Optional.empty();
        return switch (ccy.toUpperCase()) {
            case "NPR" -> Optional.of(Country.NP);
            case "INR" -> Optional.of(Country.IN);
            case "MYR" -> Optional.of(Country.MY);
            case "THB" -> Optional.of(Country.TH);
            default    -> Optional.empty();
        };
    }
}
