package com.flexpop.engine.domain.repo;

import com.flexpop.engine.domain.Country;
import com.flexpop.engine.domain.entity.GatewayMethodEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GatewayMethodRepository extends JpaRepository<GatewayMethodEntity, Long> {

    List<GatewayMethodEntity> findByCountryAndEnabledTrueOrderBySortOrderAsc(Country country);
}
