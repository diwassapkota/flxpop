package com.flexpop.engine.domain.repo;

import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.entity.TransactionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    Optional<TransactionEntity> findByPublicId(String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TransactionEntity t WHERE t.publicId = :publicId")
    Optional<TransactionEntity> findForUpdateByPublicId(@Param("publicId") String publicId);

    Optional<TransactionEntity> findByGatewayAndGatewayRef(Gateway gateway, String gatewayRef);
}
