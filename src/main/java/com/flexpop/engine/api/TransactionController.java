package com.flexpop.engine.api;

import com.flexpop.engine.api.dto.TransactionCreateRequest;
import com.flexpop.engine.api.dto.TransactionResponse;
import com.flexpop.engine.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @Valid @RequestBody TransactionCreateRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        TransactionService.TransactionOutcome outcome = service.create(req, idempotencyKey);
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header("Idempotency-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    @GetMapping("/{publicId}")
    public TransactionResponse get(@PathVariable String publicId) {
        return service.get(publicId);
    }
}
