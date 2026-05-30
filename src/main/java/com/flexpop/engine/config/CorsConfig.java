package com.flexpop.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the widget.
 *
 * The widget is hosted on a different origin than the engine API (dev: Vite
 * on :5173, engine on :8080; prod: cdn.flexpop.io vs api.flexpop.io). The
 * browser blocks cross-origin XHR without explicit CORS headers.
 *
 * We don't allow credentials (cookies) — the widget authenticates with a
 * publishable key in the Authorization header, not a cookie — so a relaxed
 * allowedOrigins is safe.
 *
 * Production should narrow this to the real widget origin(s).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://*.flexpop.io",
                        "https://*.flexpop.dev"
                )
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Idempotency-Replayed")
                .maxAge(3600);
    }
}
