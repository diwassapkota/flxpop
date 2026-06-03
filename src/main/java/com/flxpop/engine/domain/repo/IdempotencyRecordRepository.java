package com.flxpop.engine.domain.repo;

import com.flxpop.engine.domain.entity.IdempotencyRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

    Optional<IdempotencyRecordEntity> findByMerchantIdAndIdempotencyKey(
            Long merchantId, String idempotencyKey);
}
