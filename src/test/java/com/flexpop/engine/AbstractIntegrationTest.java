package com.flexpop.engine;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Base class for all engine integration tests.
 *
 *   - Talks to the local MySQL `flexpop_test` schema (no Docker required).
 *   - Auto-creates the schema on first JVM load via DB_USER/DB_PASSWORD env vars.
 *   - Drops + recreates each `mvn test` run for isolation.
 *   - Spins up a single shared WireMock as the Fonepay sandbox, pointed at via
 *     {@code flexpop.gateways.fonepay.base-url}. Default stubs echo the
 *     {@code referenceLabel} back as the PRN so each test's transaction gets a
 *     distinct {@code gatewayRef} (needed by InboundWebhookTest's lookup).
 *   - A test-fixed RSA keypair is generated once per JVM; the engine's
 *     {@code FonepaySigner} signs requests with it; no real signature
 *     verification happens against WireMock, but signing must not throw.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /** Known dev secret seeded by V2__seed_dev_api_key.sql */
    public static final String DEV_KEY = "sk_dev_local_FLEXPOPDEVKEY1234567890";
    /** Known dev publishable key seeded by V4__seed_dev_publishable_key.sql */
    public static final String DEV_PK  = "pk_dev_local_FLEXPOPPUBLICKEY1234567890";
    public static final String DEV_FONEPAY_WEBHOOK_SECRET = "dev-fonepay-webhook-secret";

    protected static final WireMockServer FONEPAY_MOCK;
    private static final String FONEPAY_PRIVATE_KEY_B64;

    static {
        bootstrapTestDatabase();

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            FONEPAY_PRIVATE_KEY_B64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new ExceptionInInitializerError("Failed to generate test RSA keypair: " + e);
        }

        FONEPAY_MOCK = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .globalTemplating(true));
        FONEPAY_MOCK.start();
        installDefaultFonepayStubs();
    }

    /** Called from per-test setup if a test wants to start from a clean stub slate. */
    protected static void resetFonepayStubs() {
        FONEPAY_MOCK.resetAll();
        installDefaultFonepayStubs();
    }

    private static void installDefaultFonepayStubs() {
        // /login → fake bearer with 1h TTL
        FONEPAY_MOCK.stubFor(post(urlEqualTo("/api/merchant/third-party/v2/login"))
                .willReturn(okJson("""
                        {"accessToken":"test-fp-bearer","tokenType":"Bearer","expiresIn":3600}
                        """)));

        // /banks/list → two banks with distinct intent schemes
        FONEPAY_MOCK.stubFor(get(urlEqualTo("/api/merchant/third-party/v2/banks/list"))
                .willReturn(okJson("""
                        {"banks":[
                          {"name":"Test Bank A","packageName":"com.test.banka","intentScheme":"TBKA"},
                          {"name":"Test Bank B","packageName":"com.test.bankb","intentScheme":"TBKB"}
                        ]}
                        """)));

        // /generate-intent-qr → echoes referenceLabel back as PRN so each txn
        // gets a distinct gatewayRef in the DB.
        FONEPAY_MOCK.stubFor(post(urlEqualTo("/api/merchant/third-party/v2/generate-intent-qr"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"qrString":"00020101021230460016me.fonepay.merchant0108TESTTERM5204000053034245405100.005802NP6304ECHO-{{jsonPath request.body '$.referenceLabel'}}",
                                 "prn":"{{jsonPath request.body '$.referenceLabel'}}",
                                 "terminalId":"{{jsonPath request.body '$.terminalId'}}",
                                 "status":"Success"}
                                """)));

        // /thirdPartyDynamicQrGetStatus → default to pending; tests override per-PRN as needed.
        FONEPAY_MOCK.stubFor(post(urlEqualTo("/api/merchant/third-party/v2/thirdPartyDynamicQrGetStatus"))
                .willReturn(okJson("""
                        {"paymentStatus":"pending","paymentMessage":"Pending","fonepayTraceId":0}
                        """)));
    }

    private static void bootstrapTestDatabase() {
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

    @DynamicPropertySource
    static void fonepayProps(DynamicPropertyRegistry r) {
        r.add("flexpop.gateways.fonepay.base-url",                 FONEPAY_MOCK::baseUrl);
        r.add("flexpop.gateways.fonepay.private-key-base64-pkcs8", () -> FONEPAY_PRIVATE_KEY_B64);
        r.add("flexpop.gateways.fonepay.terminal-id",              () -> "TEST-TERMINAL-001");
        r.add("flexpop.gateways.fonepay.login-username",           () -> "test-user");
        r.add("flexpop.gateways.fonepay.login-password",           () -> "test-pass");
        r.add("flexpop.gateways.fonepay.webhook-secret",           () -> DEV_FONEPAY_WEBHOOK_SECRET);
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
