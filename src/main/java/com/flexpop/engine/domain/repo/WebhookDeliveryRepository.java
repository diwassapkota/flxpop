package com.flexpop.engine.domain.repo;

import com.flexpop.engine.domain.entity.WebhookDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, Long> {

    /**
     * Pick up to `limit` deliveries that are due now. The `FOR UPDATE SKIP LOCKED`
     * clause in the SQL itself does the per-row locking — no @Lock annotation
     * needed (Hibernate refuses to combine @Lock with native queries anyway).
     * Safe to run from multiple worker instances concurrently.
     */
    @Query(value = """
            SELECT * FROM webhook_delivery
             WHERE status IN ('PENDING','FAILED')
               AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WebhookDeliveryEntity> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}
