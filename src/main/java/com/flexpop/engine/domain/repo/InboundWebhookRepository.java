package com.flexpop.engine.domain.repo;

import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.entity.InboundWebhookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InboundWebhookRepository extends JpaRepository<InboundWebhookEntity, Long> {

    Optional<InboundWebhookEntity> findByGatewayAndGatewayEventId(Gateway gateway, String gatewayEventId);
}
