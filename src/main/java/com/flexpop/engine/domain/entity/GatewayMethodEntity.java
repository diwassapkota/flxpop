package com.flexpop.engine.domain.entity;

import com.flexpop.engine.domain.Country;
import com.flexpop.engine.domain.Gateway;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "gateway_method")
public class GatewayMethodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Country country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Gateway gateway;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Column(name = "supports_mobile", nullable = false)
    private boolean supportsMobile = true;

    @Column(name = "supports_desktop", nullable = false)
    private boolean supportsDesktop = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(nullable = false)
    private boolean enabled = true;

    public Long getId() { return id; }
    public Country getCountry() { return country; }
    public Gateway getGateway() { return gateway; }
    public String getDisplayName() { return displayName; }
    public boolean isSupportsMobile() { return supportsMobile; }
    public boolean isSupportsDesktop() { return supportsDesktop; }
    public int getSortOrder() { return sortOrder; }
    public boolean isEnabled() { return enabled; }
}
