package com.flxpop.engine.webhook;

import com.flxpop.engine.adapter.fonepay.FonepayProperties;
import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.service.exception.BadRequestException;
import org.springframework.stereotype.Component;

/**
 * Map a Gateway to its inbound webhook signing secret. For now only Fonepay
 * is wired; future adapters register their own secret here.
 */
@Component
public class GatewaySecrets {

    private final FonepayProperties fonepay;

    public GatewaySecrets(FonepayProperties fonepay) {
        this.fonepay = fonepay;
    }

    public String secretFor(Gateway gateway) {
        return switch (gateway) {
            case FONEPAY -> fonepay.webhookSecret();
            default -> throw new BadRequestException(
                    "No inbound webhook secret configured for gateway " + gateway);
        };
    }
}
