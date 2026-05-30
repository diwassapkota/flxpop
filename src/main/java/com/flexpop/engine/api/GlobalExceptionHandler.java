package com.flexpop.engine.api;

import com.flexpop.engine.service.exception.BadGatewayException;
import com.flexpop.engine.service.exception.BadRequestException;
import com.flexpop.engine.service.exception.ForbiddenException;
import com.flexpop.engine.service.exception.IdempotencyConflictException;
import com.flexpop.engine.service.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "validation_error", message, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "malformed_body",
                "Request body could not be parsed", req);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(BadRequestException ex,
                                                          HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage(), req);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(IdempotencyConflictException ex,
                                                        HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "idempotency_conflict", ex.getMessage(), req);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex,
                                                        HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, "not_found", ex.getMessage(), req);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ForbiddenException ex,
                                                         HttpServletRequest req) {
        return body(HttpStatus.FORBIDDEN, "forbidden", ex.getMessage(), req);
    }

    @ExceptionHandler(BadGatewayException.class)
    public ResponseEntity<Map<String, Object>> badGateway(BadGatewayException ex,
                                                          HttpServletRequest req) {
        return body(HttpStatus.BAD_GATEWAY, "bad_gateway", ex.getMessage(), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest req) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unhandled(Exception ex, HttpServletRequest req) {
        String requestId = newRequestId();
        log.error("unhandled error rid={} on {} {}", requestId, req.getMethod(), req.getRequestURI(), ex);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "internal_error");
        error.put("message", "Unexpected server error");
        error.put("request_id", requestId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", error));
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String type,
                                                     String message, HttpServletRequest req) {
        String requestId = newRequestId();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", type);
        error.put("message", message == null ? "" : message);
        error.put("request_id", requestId);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }

    private static String newRequestId() {
        return "rq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
