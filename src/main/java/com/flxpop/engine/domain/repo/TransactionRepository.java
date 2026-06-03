package com.flxpop.engine.domain.repo;

import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.domain.TxnStatus;
import com.flxpop.engine.domain.entity.TransactionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    Optional<TransactionEntity> findByPublicId(String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TransactionEntity t WHERE t.publicId = :publicId")
    Optional<TransactionEntity> findForUpdateByPublicId(@Param("publicId") String publicId);

    Optional<TransactionEntity> findByGatewayAndGatewayRef(Gateway gateway, String gatewayRef);

    /**
     * Bounded fetch used by the status pollers (e.g. FonepayStatusPoller).
     * Ordered by oldest-updated first so we don't starve long-PENDING txns
     * if a noisy gateway keeps producing fresh ones.
     */
    List<TransactionEntity> findFirst25ByGatewayAndStatusInOrderByUpdatedAtAsc(
            Gateway gateway, Collection<TxnStatus> statuses);
}
