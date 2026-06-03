-- FlxPop V1 — baseline schema.
--
-- Conventions:
--   * Internal PKs: BIGINT UNSIGNED AUTO_INCREMENT.
--   * Public IDs:   VARCHAR(32), human-readable (e.g. FP-NPR-7F3A92). Always unique-indexed.
--   * Money:        BIGINT minor units (paisa/cents). Never DECIMAL or float.
--   * Timestamps:   DATETIME(3) in UTC (millisecond precision).
--   * Enums:        VARCHAR with CHECK constraint (avoid native ENUM — painful to evolve).
--   * Append-only: transaction_event is INSERT-only. State on `transaction.status` is a
--                  cache of the latest event; reconciliation can rebuild it from events alone.

-- ---------------------------------------------------------------------------
-- Merchant — tenant. Multi-tenant from day one (independent venture, not FP-internal).
-- ---------------------------------------------------------------------------
CREATE TABLE merchant (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       VARCHAR(32)     NOT NULL,
    name            VARCHAR(120)    NOT NULL,
    webhook_url     VARCHAR(500)    NULL,
    webhook_secret  VARCHAR(128)    NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                    ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_public_id (public_id),
    CONSTRAINT ck_merchant_status CHECK (status IN ('ACTIVE','SUSPENDED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- API key — for merchant auth. Out of Phase 1 scope but the table exists so the
-- schema doesn't have to migrate twice. Token stored hashed (SHA-256 hex, 64 chars).
-- ---------------------------------------------------------------------------
CREATE TABLE api_key (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    merchant_id     BIGINT UNSIGNED NOT NULL,
    key_prefix      VARCHAR(16)     NOT NULL,
    key_hash        CHAR(64)        NOT NULL,
    kind            VARCHAR(16)     NOT NULL,
    label           VARCHAR(120)    NULL,
    last_used_at    DATETIME(3)     NULL,
    revoked_at      DATETIME(3)     NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key_hash (key_hash),
    KEY ix_api_key_merchant (merchant_id),
    CONSTRAINT fk_api_key_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT ck_api_key_kind CHECK (kind IN ('SECRET','PUBLISHABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Checkout session — short-lived, country+device resolved at create time.
-- ---------------------------------------------------------------------------
CREATE TABLE checkout_session (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       VARCHAR(32)     NOT NULL,
    merchant_id     BIGINT UNSIGNED NOT NULL,
    amount_minor    BIGINT          NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    country         VARCHAR(2)      NOT NULL,
    device          VARCHAR(8)      NOT NULL,
    merchant_ref    VARCHAR(120)    NULL,
    methods_json    JSON            NOT NULL,
    expires_at      DATETIME(3)     NOT NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_public_id (public_id),
    KEY ix_session_merchant_created (merchant_id, created_at),
    CONSTRAINT fk_session_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT ck_session_amount   CHECK (amount_minor > 0),
    CONSTRAINT ck_session_currency CHECK (currency IN ('NPR','INR','MYR','THB')),
    CONSTRAINT ck_session_country  CHECK (country  IN ('NP','IN','MY','TH')),
    CONSTRAINT ck_session_device   CHECK (device   IN ('MOBILE','DESKTOP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Transaction — one per attempted payment. status is the cached projection of
-- the latest transaction_event row. Reconciliation can rebuild it from events.
-- ---------------------------------------------------------------------------
CREATE TABLE transaction (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id           VARCHAR(32)     NOT NULL,
    merchant_id         BIGINT UNSIGNED NOT NULL,
    session_id          BIGINT UNSIGNED NOT NULL,
    amount_minor        BIGINT          NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    country             VARCHAR(2)      NOT NULL,
    device              VARCHAR(8)      NOT NULL,
    gateway             VARCHAR(16)     NOT NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'CREATED',
    gateway_ref         VARCHAR(120)    NULL,
    app_intent_url      VARCHAR(2048)   NULL,
    qr_payload          TEXT            NULL,
    expires_at          DATETIME(3)     NULL,
    settled_at          DATETIME(3)     NULL,
    failure_code        VARCHAR(64)     NULL,
    failure_message     VARCHAR(500)    NULL,
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_txn_public_id (public_id),
    KEY ix_txn_merchant_created (merchant_id, created_at),
    KEY ix_txn_session (session_id),
    KEY ix_txn_gateway_ref (gateway, gateway_ref),
    KEY ix_txn_status_created (status, created_at),
    CONSTRAINT fk_txn_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT fk_txn_session  FOREIGN KEY (session_id)  REFERENCES checkout_session(id),
    CONSTRAINT ck_txn_amount   CHECK (amount_minor > 0),
    CONSTRAINT ck_txn_currency CHECK (currency IN ('NPR','INR','MYR','THB')),
    CONSTRAINT ck_txn_country  CHECK (country  IN ('NP','IN','MY','TH')),
    CONSTRAINT ck_txn_device   CHECK (device   IN ('MOBILE','DESKTOP')),
    CONSTRAINT ck_txn_status   CHECK (status   IN
        ('CREATED','ROUTED','PENDING','SETTLED','FAILED','EXPIRED','REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Transaction event — APPEND-ONLY ledger. The source of truth.
-- ---------------------------------------------------------------------------
CREATE TABLE transaction_event (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       VARCHAR(36)     NOT NULL,
    transaction_id  BIGINT UNSIGNED NOT NULL,
    type            VARCHAR(32)     NOT NULL,
    source          VARCHAR(16)     NOT NULL,
    payload_json    JSON            NULL,
    occurred_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_txn_event_public_id (public_id),
    KEY ix_txn_event_txn_time (transaction_id, occurred_at),
    KEY ix_txn_event_type_time (type, occurred_at),
    CONSTRAINT fk_txn_event_txn FOREIGN KEY (transaction_id) REFERENCES transaction(id),
    CONSTRAINT ck_txn_event_source CHECK (source IN ('ENGINE','GATEWAY','MERCHANT','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Idempotency record — dedupes retried POSTs.
-- Stores the original response body so replays return the same answer.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    merchant_id     BIGINT UNSIGNED NOT NULL,
    idempotency_key VARCHAR(128)    NOT NULL,
    request_path    VARCHAR(255)    NOT NULL,
    request_hash    CHAR(64)        NOT NULL,
    response_status SMALLINT        NULL,
    response_body   JSON            NULL,
    transaction_id  BIGINT UNSIGNED NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at    DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idem_merchant_key (merchant_id, idempotency_key),
    CONSTRAINT fk_idem_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT fk_idem_txn      FOREIGN KEY (transaction_id) REFERENCES transaction(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Webhook delivery — outbound queue. Engine → merchant.
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_delivery (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       VARCHAR(36)     NOT NULL,
    merchant_id     BIGINT UNSIGNED NOT NULL,
    transaction_id  BIGINT UNSIGNED NULL,
    event_type      VARCHAR(32)     NOT NULL,
    payload_json    JSON            NOT NULL,
    target_url      VARCHAR(500)    NOT NULL,
    signature       CHAR(64)        NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    attempts        INT             NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3)     NOT NULL,
    last_response   SMALLINT        NULL,
    last_error      VARCHAR(500)    NULL,
    delivered_at    DATETIME(3)     NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_public_id (public_id),
    KEY ix_webhook_due (status, next_attempt_at),
    KEY ix_webhook_merchant_created (merchant_id, created_at),
    CONSTRAINT fk_webhook_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT fk_webhook_txn      FOREIGN KEY (transaction_id) REFERENCES transaction(id),
    CONSTRAINT ck_webhook_status   CHECK (status IN ('PENDING','IN_FLIGHT','DELIVERED','FAILED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Inbound webhook event — gateway → engine. Dedup on signed event id.
-- ---------------------------------------------------------------------------
CREATE TABLE inbound_webhook (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    gateway         VARCHAR(16)     NOT NULL,
    gateway_event_id VARCHAR(120)   NOT NULL,
    transaction_id  BIGINT UNSIGNED NULL,
    type            VARCHAR(32)     NULL,
    raw_body        MEDIUMTEXT      NOT NULL,
    signature       VARCHAR(256)    NULL,
    received_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    processed_at    DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound_gateway_event (gateway, gateway_event_id),
    KEY ix_inbound_txn (transaction_id),
    CONSTRAINT fk_inbound_txn FOREIGN KEY (transaction_id) REFERENCES transaction(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Routing rules — country × device → ordered gateway list.
-- Seeded below; can be overridden per merchant later.
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_method (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    country         VARCHAR(2)      NOT NULL,
    gateway         VARCHAR(16)     NOT NULL,
    display_name    VARCHAR(60)     NOT NULL,
    supports_mobile TINYINT(1)      NOT NULL DEFAULT 1,
    supports_desktop TINYINT(1)     NOT NULL DEFAULT 1,
    sort_order      INT             NOT NULL DEFAULT 100,
    enabled         TINYINT(1)      NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_method_country_gateway (country, gateway),
    KEY ix_method_lookup (country, enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO gateway_method (country, gateway, display_name, sort_order) VALUES
    ('NP', 'FONEPAY',   'Fonepay',     10),
    ('NP', 'ESEWA',     'eSewa',       20),
    ('IN', 'UPI',       'UPI / GPay',  10),
    ('IN', 'PAYTM',     'Paytm',       20),
    ('MY', 'TNG',       'Touch ’n Go', 10),
    ('MY', 'FPX',       'FPX',         20),
    ('TH', 'PROMPTPAY', 'PromptPay',   10),
    ('TH', 'TRUEMONEY', 'TrueMoney',   20);

-- ---------------------------------------------------------------------------
-- Seed: a dev merchant so we can hit the API immediately during Phase 1.
-- Replace before any pilot.
-- ---------------------------------------------------------------------------
INSERT INTO merchant (public_id, name, webhook_url, webhook_secret) VALUES
    ('MCH-DEV-LOCAL', 'Dev Merchant (local)', 'http://localhost:9090/flxpop-webhook',
     'dev-webhook-secret-change-me');
