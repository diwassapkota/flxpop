-- V2 — seed a known dev API key for the MCH-DEV-LOCAL merchant.
--
-- DEV PLAINTEXT (for local curl only; rotate before any pilot):
--   sk_dev_local_FLXPOPDEVKEY1234567890
--
-- Stored:
--   key_prefix:  sk_dev_local_FLE      (first 16 chars; safe to log)
--   key_hash:    SHA-256 of the full plaintext (lowercase hex)
--
-- Recompute hash if you change the plaintext:
--   printf 'sk_dev_local_FLXPOPDEVKEY1234567890' | shasum -a 256

INSERT INTO api_key (merchant_id, key_prefix, key_hash, kind, label)
SELECT m.id,
       'sk_dev_local_FLE',
       'a027d90ddaf3ec6af3e7dfbc78451229263400fba7047cb5c21a25bbba20d654',
       'SECRET',
       'Dev local secret key (FLXPOPDEVKEY1234567890)'
  FROM merchant m
 WHERE m.public_id = 'MCH-DEV-LOCAL'
   AND NOT EXISTS (
       SELECT 1 FROM api_key k
        WHERE k.key_hash = 'a027d90ddaf3ec6af3e7dfbc78451229263400fba7047cb5c21a25bbba20d654'
   );
