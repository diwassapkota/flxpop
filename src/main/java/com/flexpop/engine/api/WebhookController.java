package com.flexpop.engine.api;

import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.webhook.InboundWebhookService;
import com.flexpop.engine.webhook.InboundWebhookService.InboundResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/webhooks/gateways")
public class WebhookController {

    private final InboundWebhookService service;

    public WebhookController(InboundWebhookService service) {
        this.service = service;
    }

    @PostMapping(value = "/{gateway}", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable("gateway") Gateway gateway,
            @RequestBody String rawBody,
            @RequestHeader(value = "FP-Signature", required = false) String signature) {

        InboundResult result = service.handle(gateway, rawBody, signature);
        return switch (result) {
            case PROCESSED -> ResponseEntity.ok(Map.of("status", "processed"));
            case DUPLICATE -> ResponseEntity.ok(Map.of("status", "duplicate"));
            case BAD_SIGNATURE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", Map.of(
                            "type", "bad_signature",
                            "message", "FP-Signature header missing or invalid")));
        };
    }
}
