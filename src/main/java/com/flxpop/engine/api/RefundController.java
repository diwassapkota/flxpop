package com.flxpop.engine.api;

import com.flxpop.engine.api.dto.RefundCreateRequest;
import com.flxpop.engine.api.dto.RefundResponse;
import com.flxpop.engine.auth.RequiresSecretKey;
import com.flxpop.engine.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transactions/{txnId}/refunds")
public class RefundController {

    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresSecretKey
    public ResponseEntity<RefundResponse> create(
            @PathVariable("txnId") String txnId,
            @Valid @RequestBody RefundCreateRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        RefundService.RefundOutcome outcome = service.create(txnId, req, idempotencyKey);
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .header("Idempotency-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }
}
