package com.flxpop.engine.domain.repo;

import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.domain.entity.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<RefundEntity, Long> {

    Optional<RefundEntity> findByPublicId(String publicId);

    Optional<RefundEntity> findByGatewayAndGatewayRef(Gateway gateway, String gatewayRef);

    List<RefundEntity> findByTransactionIdOrderByCreatedAtAsc(Long transactionId);
}
