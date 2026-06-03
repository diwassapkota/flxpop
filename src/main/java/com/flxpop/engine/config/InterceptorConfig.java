package com.flxpop.engine.config;

import com.flxpop.engine.auth.SecretKeyOnlyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires {@link SecretKeyOnlyInterceptor} into the MVC pipeline for
 * {@code /v1/**}. The interceptor runs after {@code ApiKeyAuthFilter} (which
 * is a servlet filter, so always before MVC) — by the time it inspects the
 * handler annotation, the request already has its key kind attached.
 */
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

    private final SecretKeyOnlyInterceptor secretKeyOnlyInterceptor;

    public InterceptorConfig(SecretKeyOnlyInterceptor secretKeyOnlyInterceptor) {
        this.secretKeyOnlyInterceptor = secretKeyOnlyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(secretKeyOnlyInterceptor).addPathPatterns("/v1/**");
    }
}
