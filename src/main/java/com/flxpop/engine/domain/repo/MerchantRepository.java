package com.flxpop.engine.domain.repo;

import com.flxpop.engine.domain.entity.MerchantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantRepository extends JpaRepository<MerchantEntity, Long> {
    Optional<MerchantEntity> findByPublicId(String publicId);
}
