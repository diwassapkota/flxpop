-- V4 — seed a known dev PUBLISHABLE key for the widget.
--
-- DEV PLAINTEXT (safe to ship in browser source — that's the point of pk_):
--   pk_dev_local_FLEXPOPPUBLICKEY1234567890
--
-- Stored:
--   key_prefix: pk_dev_local_FLE
--   key_hash:   SHA-256 of full plaintext (lowercase hex)
--
-- Recompute hash if you change the plaintext:
--   printf 'pk_dev_local_FLEXPOPPUBLICKEY1234567890' | shasum -a 256
--
-- Phase-1 simplification: pk and sk currently have the same engine privileges
-- via ApiKeyAuthFilter. Scoping pk_* to only POST /v1/transactions for sessions
-- it was issued against is a later round.

INSERT INTO api_key (merchant_id, key_prefix, key_hash, kind, label)
SELECT m.id,
       'pk_dev_local_FLE',
       '56c0af91ea42455e08491b6dc48dc55a7aca28612eece6488a3074e551ccec95',
       'PUBLISHABLE',
       'Dev local publishable key (FLEXPOPPUBLICKEY...) — safe to embed in browser'
  FROM merchant m
 WHERE m.public_id = 'MCH-DEV-LOCAL'
   AND NOT EXISTS (
       SELECT 1 FROM api_key k
        WHERE k.key_hash = '56c0af91ea42455e08491b6dc48dc55a7aca28612eece6488a3074e551ccec95'
   );
