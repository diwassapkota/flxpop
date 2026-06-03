package com.flxpop.engine.domain.repo;

import com.flxpop.engine.domain.Country;
import com.flxpop.engine.domain.entity.GatewayMethodEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GatewayMethodRepository extends JpaRepository<GatewayMethodEntity, Long> {

    List<GatewayMethodEntity> findByCountryAndEnabledTrueOrderBySortOrderAsc(Country country);
}
