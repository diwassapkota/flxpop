package com.flxpop.engine.config;

import com.flxpop.engine.adapter.esewa.EsewaProperties;
import com.flxpop.engine.adapter.fonepay.FonepayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({FonepayProperties.class, EsewaProperties.class})
public class PropertiesConfig {
}
