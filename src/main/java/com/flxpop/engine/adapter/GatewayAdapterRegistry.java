package com.flxpop.engine.adapter;

import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.service.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class GatewayAdapterRegistry {

    private final Map<Gateway, GatewayAdapter> byGateway;

    public GatewayAdapterRegistry(List<GatewayAdapter> adapters) {
        Map<Gateway, GatewayAdapter> map = new EnumMap<>(Gateway.class);
        for (GatewayAdapter a : adapters) {
            map.put(a.gateway(), a);
        }
        this.byGateway = map;
    }

    public GatewayAdapter require(Gateway gateway) {
        GatewayAdapter a = byGateway.get(gateway);
        if (a == null) {
            throw new BadRequestException("Gateway " + gateway + " has no adapter wired");
        }
        return a;
    }

    public boolean supports(Gateway gateway) {
        return byGateway.containsKey(gateway);
    }
}
