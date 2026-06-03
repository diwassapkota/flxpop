package com.flxpop.engine.domain.repo;

import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.domain.entity.InboundWebhookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InboundWebhookRepository extends JpaRepository<InboundWebhookEntity, Long> {

    Optional<InboundWebhookEntity> findByGatewayAndGatewayEventId(Gateway gateway, String gatewayEventId);
}
