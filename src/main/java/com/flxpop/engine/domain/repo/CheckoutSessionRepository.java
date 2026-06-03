package com.flxpop.engine.domain.repo;

import com.flxpop.engine.domain.entity.CheckoutSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CheckoutSessionRepository extends JpaRepository<CheckoutSessionEntity, Long> {
    Optional<CheckoutSessionEntity> findByPublicId(String publicId);
}
