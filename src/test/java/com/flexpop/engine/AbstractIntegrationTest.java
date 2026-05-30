package com.flexpop.engine;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Base class for all engine integration tests.
 *
 *   - Talks to the local MySQL `flexpop_test` schema (no Docker required).
 *     Connection details come from application-test.yml + env vars.
 *   - Auto-creates the `flexpop_test` schema on first JVM load so you don't
 *     have to set it up manually — uses the same DB_USER/DB_PASSWORD as the
 *     main app, which must have CREATE privileges.
 *   - Flyway runs V1 + V2 migrations on first boot. Subsequent runs are no-ops
 *     (seed inserts are idempotent via WHERE NOT EXISTS).
 *   - Each test creates its own session/transaction with random IDs, so state
 *     accumulating across runs doesn't cause collisions.
 *   - If you later want isolated containerized tests, swap in
 *     `@Testcontainers` + `MySQLContainer` here — the test bodies don't change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /** Known dev secret seeded by V2__seed_dev_api_key.sql */
    public static final String DEV_KEY = "sk_dev_local_FLEXPOPDEVKEY1234567890";
    public static final String DEV_FONEPAY_WEBHOOK_SECRET = "dev-fonepay-webhook-secret";

    static {
        try {
            String host = env("DB_HOST", "127.0.0.1");
            String port = env("DB_PORT", "3306");
            String user = env("DB_USER", "root");
            String pass = env("DB_PASSWORD", "");
            String db   = env("TEST_DB_NAME", "flexpop_test");

            Class.forName("com.mysql.cj.jdbc.Driver");
            String adminUrl = "jdbc:mysql://" + host + ":" + port
                    + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            try (Connection c = DriverManager.getConnection(adminUrl, user, pass);
                 Statement s = c.createStatement()) {
                // Drop + recreate so every `mvn test` starts from a clean slate.
                // Flyway re-runs V1+V2 against the fresh schema.
                s.execute("DROP DATABASE IF EXISTS " + db);
                s.execute("CREATE DATABASE " + db
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(
                    "Failed to bootstrap test database — set DB_USER/DB_PASSWORD env vars: "
                            + e.getMessage());
        }
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }

    @LocalServerPort
    protected int port;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
