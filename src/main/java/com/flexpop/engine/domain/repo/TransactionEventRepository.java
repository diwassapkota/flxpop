package com.flexpop.engine.domain.repo;

import com.flexpop.engine.domain.entity.TransactionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionEventRepository extends JpaRepository<TransactionEventEntity, Long> {
    List<TransactionEventEntity> findByTransactionIdOrderByOccurredAtAsc(Long transactionId);
}
