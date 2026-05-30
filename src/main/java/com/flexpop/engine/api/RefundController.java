package com.flexpop.engine.api;

import com.flexpop.engine.api.dto.RefundCreateRequest;
import com.flexpop.engine.api.dto.RefundResponse;
import com.flexpop.engine.auth.RequiresSecretKey;
import com.flexpop.engine.service.RefundService;
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
