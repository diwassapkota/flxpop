package com.flxpop.engine.service;

import com.flxpop.engine.api.dto.SessionCreateRequest;
import com.flxpop.engine.api.dto.SessionResponse;
import com.flxpop.engine.domain.Country;
import com.flxpop.engine.domain.Device;
import com.flxpop.engine.domain.PublicIdGenerator;
import com.flxpop.engine.domain.entity.CheckoutSessionEntity;
import com.flxpop.engine.domain.entity.MerchantEntity;
import com.flxpop.engine.domain.repo.CheckoutSessionRepository;
import com.flxpop.engine.routing.CountryResolver;
import com.flxpop.engine.routing.DeviceResolver;
import com.flxpop.engine.routing.RoutingService;
import com.flxpop.engine.service.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class SessionService {

    private static final Duration SESSION_TTL = Duration.ofMinutes(15);

    private final CheckoutSessionRepository sessionRepo;
    private final MerchantContext merchantContext;
    private final RoutingService routing;
    private final CountryResolver countryResolver;
    private final DeviceResolver deviceResolver;

    public SessionService(CheckoutSessionRepository sessionRepo,
                          MerchantContext merchantContext,
                          RoutingService routing,
                          CountryResolver countryResolver,
                          DeviceResolver deviceResolver) {
        this.sessionRepo = sessionRepo;
        this.merchantContext = merchantContext;
        this.routing = routing;
        this.countryResolver = countryResolver;
        this.deviceResolver = deviceResolver;
    }

    @Transactional
    public SessionResponse create(SessionCreateRequest req, HttpServletRequest http) {
        MerchantEntity merchant = merchantContext.current();
        Country country = countryResolver.resolve(http, req.country(), req.currency().name());
        Device device = deviceResolver.resolve(http);

        if (country.defaultCurrency() != req.currency()) {
            throw new BadRequestException(
                    "Currency " + req.currency() + " does not match country " + country);
        }

        List<RoutingService.RoutedMethod> methods = routing.resolve(country, device);
        if (methods.isEmpty()) {
            throw new BadRequestException(
                    "No payment methods configured for " + country + " on " + device);
        }

        CheckoutSessionEntity entity = new CheckoutSessionEntity();
        entity.setPublicId(PublicIdGenerator.forSession());
        entity.setMerchantId(merchant.getId());
        entity.setAmountMinor(req.amount());
        entity.setCurrency(req.currency());
        entity.setCountry(country);
        entity.setDevice(device);
        entity.setMerchantRef(req.reference());
        entity.setMethods(methods.stream().map(m -> m.gateway().name()).toList());
        entity.setExpiresAt(Instant.now().plus(SESSION_TTL));

        CheckoutSessionEntity saved = sessionRepo.save(entity);

        List<SessionResponse.MethodSummary> summary = methods.stream()
                .map(m -> new SessionResponse.MethodSummary(m.gateway().name(), m.displayName()))
                .toList();

        return new SessionResponse(
                saved.getPublicId(),
                saved.getAmountMinor(),
                saved.getCurrency(),
                saved.getCountry(),
                saved.getDevice(),
                summary,
                saved.getExpiresAt()
        );
    }
}
