package com.flxpop.engine.domain.repo;

import com.flxpop.engine.domain.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    Optional<ApiKeyEntity> findByKeyHashAndRevokedAtIsNull(String keyHash);

    /**
     * Fire-and-forget last_used_at touch. Don't bother making this transactional with
     * the request — if the write loses to a crash, we update on the next request.
     */
    @Modifying
    @Query("UPDATE ApiKeyEntity k SET k.lastUsedAt = :now WHERE k.id = :id")
    void touchLastUsed(@Param("id") Long id, @Param("now") Instant now);
}
