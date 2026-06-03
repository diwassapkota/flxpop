package com.flxpop.engine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS for the widget.
 *
 * The widget is hosted on a different origin than the engine API (dev: Vite
 * on :5173, engine on :8080; prod: the web container's domain vs the engine's).
 * The browser blocks cross-origin XHR without explicit CORS headers.
 *
 * We don't allow credentials (cookies) — the widget authenticates with a
 * publishable key in the Authorization header, not a cookie — so relaxed
 * origin patterns are safe.
 *
 * <p>Origins are configurable via {@code flxpop.cors.allowed-origin-patterns}
 * (env: {@code FLXPOP_CORS_ALLOWED_ORIGIN_PATTERNS}, comma-separated). The
 * default list covers local dev + LAN testing; in production set it to the real
 * web origin(s), e.g. {@code https://checkout.example.com}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOriginPatterns;

    public CorsConfig(
            @Value("${flxpop.cors.allowed-origin-patterns}") List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOriginPatterns(allowedOriginPatterns.toArray(new String[0]))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Idempotency-Replayed")
                .maxAge(3600);
    }
}
