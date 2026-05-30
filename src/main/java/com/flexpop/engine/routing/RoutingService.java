package com.flexpop.engine.routing;

import com.flexpop.engine.domain.Country;
import com.flexpop.engine.domain.Device;
import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.entity.GatewayMethodEntity;
import com.flexpop.engine.domain.repo.GatewayMethodRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Country × Device → ordered list of payment methods. Server-driven, never
 * trusts merchant or client hints for the decision itself (see DeviceResolver
 * for why `device_hint` from the spec is intentionally not honored).
 */
@Service
public class RoutingService {

    private final GatewayMethodRepository repo;

    public RoutingService(GatewayMethodRepository repo) {
        this.repo = repo;
    }

    public List<RoutedMethod> resolve(Country country, Device device) {
        return repo.findByCountryAndEnabledTrueOrderBySortOrderAsc(country).stream()
                .filter(m -> switch (device) {
                    case MOBILE  -> m.isSupportsMobile();
                    case DESKTOP -> m.isSupportsDesktop();
                })
                .map(this::toRouted)
                .toList();
    }

    private RoutedMethod toRouted(GatewayMethodEntity m) {
        return new RoutedMethod(m.getGateway(), m.getDisplayName());
    }

    public record RoutedMethod(Gateway gateway, String displayName) { }
}
