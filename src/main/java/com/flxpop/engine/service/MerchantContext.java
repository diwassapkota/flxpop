package com.flxpop.engine.service;

import com.flxpop.engine.auth.AuthAttributes;
import com.flxpop.engine.domain.entity.MerchantEntity;
import com.flxpop.engine.service.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the currently-authenticated merchant from the request scope.
 * ApiKeyAuthFilter puts the resolved MerchantEntity on the request as an
 * attribute; this just reads it.
 *
 * Throws NotFoundException if called outside an HTTP request (e.g. from the
 * scheduled outbound worker — which shouldn't need a merchant context anyway).
 */
@Component
public class MerchantContext {

    public MerchantEntity current() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new NotFoundException(
                    "No HTTP request in scope — MerchantContext.current() called from a non-request thread");
        }
        HttpServletRequest req = attrs.getRequest();
        Object merchant = req.getAttribute(AuthAttributes.MERCHANT);
        if (!(merchant instanceof MerchantEntity m)) {
            throw new NotFoundException(
                    "Request reached MerchantContext without authentication — filter bypassed?");
        }
        return m;
    }
}
