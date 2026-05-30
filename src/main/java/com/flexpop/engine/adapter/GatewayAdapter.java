package com.flexpop.engine.adapter;

import com.flexpop.engine.domain.Country;
import com.flexpop.engine.domain.Device;
import com.flexpop.engine.domain.Gateway;
import com.flexpop.engine.domain.Money;

import java.time.Instant;

/**
 * The contract every L3 gateway sits behind. Per FP-SPEC-001 §1.
 *
 *   initiate()    — call gateway to start a payment; return intent URL or QR payload
 *   queryStatus() — pull status (used for fallback / reconciliation)
 *   handleCallback — TODO when we have a non-Fonepay-shaped callback. The
 *                    inbound webhook handler does the dispatch.
 *
 * Adapters are stateless. State lives on the engine ledger.
 */
public interface GatewayAdapter {

    Gateway gateway();

    InitiateResult initiate(InitiateRequest req);

    StatusResult queryStatus(String gatewayRef);

    /**
     * Issue a refund against a settled transaction. Some gateways don't support
     * API-driven refunds — those throw RefundNotSupported and the merchant has to
     * settle off-band. Default impl says "not supported" so adapters opt in.
     */
    default RefundResult refund(RefundRequest req) {
        throw new RefundNotSupported(gateway());
    }

    record InitiateRequest(
            String txnPublicId,
            Money amount,
            Country country,
            Device device,
            String merchantRef
    ) { }

    record InitiateResult(
            String gatewayRef,
            String appIntentUrl,
            String qrPayload,
            Instant expiresAt
    ) { }

    record StatusResult(
            String gatewayRef,
            String status,
            Instant settledAt,
            String failureCode,
            String failureMessage
    ) { }

    record RefundRequest(
            String refundPublicId,
            String originalTxnGatewayRef,
            Money amount,
            String reason
    ) { }

    record RefundResult(
            String gatewayRef,
            String status
    ) { }

    class RefundNotSupported extends RuntimeException {
        public RefundNotSupported(Gateway gateway) {
            super("Gateway " + gateway + " does not support API-driven refunds");
        }
    }
}
