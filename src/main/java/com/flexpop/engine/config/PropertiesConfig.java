package com.flexpop.engine.config;

import com.flexpop.engine.adapter.esewa.EsewaProperties;
import com.flexpop.engine.adapter.fonepay.FonepayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({FonepayProperties.class, EsewaProperties.class})
public class PropertiesConfig {
}
