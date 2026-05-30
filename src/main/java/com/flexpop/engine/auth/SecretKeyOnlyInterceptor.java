package com.flexpop.engine.auth;

import com.flexpop.engine.domain.ApiKeyKind;
import com.flexpop.engine.service.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * If the handler method is annotated {@link RequiresSecretKey}, requests
 * authenticated with a {@link ApiKeyKind#PUBLISHABLE} key are rejected.
 *
 * <p>The {@link ApiKeyAuthFilter} runs first and attaches the key kind to
 * the request; this interceptor only consults that attribute. Webhook routes
 * are whitelisted out of the filter (they auth via HMAC), so this
 * interceptor never sees them either.
 */
@Component
public class SecretKeyOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresSecretKey annotation = handlerMethod.getMethodAnnotation(RequiresSecretKey.class);
        if (annotation == null) {
            return true;
        }
        Object kind = request.getAttribute(AuthAttributes.API_KEY_KIND);
        if (kind != ApiKeyKind.SECRET) {
            throw new ForbiddenException(
                    "This endpoint requires a secret (sk_) key; publishable (pk_) keys are not authorized");
        }
        return true;
    }
}
