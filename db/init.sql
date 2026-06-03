-- FlxPop — one-time local MySQL bootstrap.
-- Run as your MySQL root (or any user with CREATE/GRANT privileges):
--   mysql -u root -p < db/init.sql
--
-- Creates the application database, a dedicated app user, and grants.
-- Idempotent — safe to re-run.

CREATE DATABASE IF NOT EXISTS flxpop
  CHARACTER SET utf8mb4
  COLLATE       utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS flxpop_test
  CHARACTER SET utf8mb4
  COLLATE       utf8mb4_0900_ai_ci;

-- App user. Change the password before sharing this file.
-- Then set FLXPOP_DB_PASSWORD in your shell to match.
CREATE USER IF NOT EXISTS 'flxpop_app'@'127.0.0.1'
  IDENTIFIED BY 'change-me-locally';

GRANT ALL PRIVILEGES ON flxpop.*      TO 'flxpop_app'@'127.0.0.1';
GRANT ALL PRIVILEGES ON flxpop_test.* TO 'flxpop_app'@'127.0.0.1';

FLUSH PRIVILEGES;

SELECT 'flxpop databases ready' AS status;
