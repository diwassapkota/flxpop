-- V3 — refunds.
--
-- Modelled as a sibling resource to transaction, not a status. Reasons:
--   * Spec path is /transactions/{id}/refunds (plural) — multiple refunds per txn
--   * Partial refunds total to the original amount across N rows
--   * Each refund has its own gateway_ref for the inbound webhook to dispatch on
--
-- Cached projection on transaction: refunded_amount_minor (incremented on each
-- successful refund). When refunded_amount_minor == amount_minor we flip
-- transaction.status to REFUNDED. Source of truth is still the refund rows.

ALTER TABLE transaction
    ADD COLUMN refunded_amount_minor BIGINT NOT NULL DEFAULT 0 AFTER amount_minor;

CREATE TABLE refund (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       VARCHAR(32)     NOT NULL,
    transaction_id  BIGINT UNSIGNED NOT NULL,
    merchant_id     BIGINT UNSIGNED NOT NULL,
    amount_minor    BIGINT          NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    gateway         VARCHAR(16)     NOT NULL,
    gateway_ref     VARCHAR(120)    NULL,
    reason          VARCHAR(500)    NULL,
    failure_code    VARCHAR(64)     NULL,
    failure_message VARCHAR(500)    NULL,
    settled_at      DATETIME(3)     NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                    ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_public_id (public_id),
    KEY ix_refund_txn (transaction_id),
    KEY ix_refund_merchant_created (merchant_id, created_at),
    KEY ix_refund_gateway_ref (gateway, gateway_ref),
    CONSTRAINT fk_refund_txn      FOREIGN KEY (transaction_id) REFERENCES transaction(id),
    CONSTRAINT fk_refund_merchant FOREIGN KEY (merchant_id)    REFERENCES merchant(id),
    CONSTRAINT ck_refund_amount   CHECK (amount_minor > 0),
    CONSTRAINT ck_refund_currency CHECK (currency IN ('NPR','INR','MYR','THB')),
    CONSTRAINT ck_refund_status   CHECK (status IN ('PENDING','SETTLED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
