-- FlexPop — one-time local MySQL bootstrap.
-- Run as your MySQL root (or any user with CREATE/GRANT privileges):
--   mysql -u root -p < db/init.sql
--
-- Creates the application database, a dedicated app user, and grants.
-- Idempotent — safe to re-run.

CREATE DATABASE IF NOT EXISTS flexpop
  CHARACTER SET utf8mb4
  COLLATE       utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS flexpop_test
  CHARACTER SET utf8mb4
  COLLATE       utf8mb4_0900_ai_ci;

-- App user. Change the password before sharing this file.
-- Then set FLEXPOP_DB_PASSWORD in your shell to match.
CREATE USER IF NOT EXISTS 'flexpop_app'@'127.0.0.1'
  IDENTIFIED BY 'change-me-locally';

GRANT ALL PRIVILEGES ON flexpop.*      TO 'flexpop_app'@'127.0.0.1';
GRANT ALL PRIVILEGES ON flexpop_test.* TO 'flexpop_app'@'127.0.0.1';

FLUSH PRIVILEGES;

SELECT 'flexpop databases ready' AS status;
