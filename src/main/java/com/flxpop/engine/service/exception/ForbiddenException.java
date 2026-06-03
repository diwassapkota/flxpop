package com.flxpop.engine.service.exception;

/**
 * Authenticated but not allowed. Mapped to HTTP 403 by
 * {@code GlobalExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
