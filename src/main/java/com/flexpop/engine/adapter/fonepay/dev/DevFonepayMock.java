package com.flexpop.engine.adapter.fonepay.dev;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * In-process Fonepay sandbox simulator for local demos. Activate with the
 * Spring profile {@code dev-fonepay-mock} — companion {@code application-dev-fonepay-mock.yml}
 * overrides {@code flexpop.gateways.fonepay.base-url} to point here.
 *
 * <p>Once the real sandbox credentials are available, just stop activating
 * this profile and unset the base-url override; the same engine code calls
 * the real Fonepay without further changes.
 *
 * <h2>Drive a successful payment from the terminal</h2>
 * <pre>{@code
 *   # 1) flip status stub to success — the poller picks it up within ~5s
 *   curl -X POST http://localhost:8089/__admin/mappings -H 'Content-Type: application/json' -d '{
 *     "priority": 1,
 *     "request":  {"method":"POST","url":"/api/merchant/third-party/v2/thirdPartyDynamicQrGetStatus"},
 *     "response": {"status":200,"headers":{"Content-Type":"application/json"},
 *                   "jsonBody":{"paymentStatus":"success","paymentMessage":"Payment success","fonepayTraceId":99999}}
 *   }'
 *
 *   # 2) back to pending — add a priority-1 PENDING override on top of the success one.
 *   #    Do NOT use POST /__admin/mappings/reset: these stubs are registered
 *   #    programmatically (not from files), so reset wipes ALL of them — including
 *   #    login / banks / generate-intent-qr — and restores nothing, breaking the mock.
 *   #    To get a clean baseline, restart the engine instead.
 *   curl -X POST http://localhost:8089/__admin/mappings -H 'Content-Type: application/json' -d '{
 *     "priority": 1,
 *     "request":  {"method":"POST","url":"/api/merchant/third-party/v2/thirdPartyDynamicQrGetStatus"},
 *     "response": {"status":200,"headers":{"Content-Type":"application/json"},
 *                   "jsonBody":{"paymentStatus":"pending","paymentMessage":"Awaiting payment","fonepayTraceId":0}}
 *   }'
 * }</pre>
 */
@Component
@Profile("dev-fonepay-mock")
public class DevFonepayMock {

    private static final Logger log = LoggerFactory.getLogger(DevFonepayMock.class);
    public static final int PORT = 8089;

    private WireMockServer server;

    @PostConstruct
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options()
                .port(PORT)
                .globalTemplating(true));
        server.start();
        installDefaultStubs();
        log.info("DevFonepayMock active on http://localhost:{}/  (admin: /__admin)", PORT);
    }

    @PreDestroy
    public void stop() {
        if (server != null) server.stop();
    }

    private void installDefaultStubs() {
        server.stubFor(post(urlEqualTo("/api/merchant/third-party/v2/login"))
                .willReturn(okJson("""
                        {"accessToken":"Bearer dev-mock-bearer","tokenType":"Bearer","expiresIn":3600}
                        """)));
                        // Real Fonepay returns accessToken ALREADY prefixed with "Bearer " —
                        // mirror that so the auth interceptor's de-dup path is exercised locally.

        // Bank list — mirrors the REAL sandbox wire shape: array key "bankDetails",
        // name field "bankName", and intentScheme as a full URI prefix ending in
        // its path (e.g. "fonepay://payment/"), NOT a bare scheme word.
        server.stubFor(get(urlEqualTo("/api/merchant/third-party/v2/banks/list"))
                .willReturn(okJson("""
                        {"bankDetails":[
                          {"bankName":"NIC Asia Bank","bankCode":"NICENPKA","bankIcon":"https://fonepay.com/icons/nicasia.png","packageName":"com.nicasiabank.smartxapp","intentScheme":"nicasia://payment/"},
                          {"bankName":"Nabil Bank","bankCode":"NARBNPKA","bankIcon":"https://fonepay.com/icons/nabil.png","packageName":"com.f1soft.nabilmbanking","intentScheme":"nabil://payment/"},
                          {"bankName":"Global IME Bank","bankCode":"GLBBNPKA","bankIcon":"https://fonepay.com/icons/globalime.png","packageName":"com.f1soft.global.smartbank","intentScheme":"fonepay://payment/"}
                        ]}
                        """)));

        // Echo referenceLabel back as PRN so each txn has a distinct gateway_ref
        // (the engine's poller and webhook handler key on it).
        server.stubFor(post(urlEqualTo("/api/merchant/third-party/v2/generate-intent-qr"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"qrString":"00020101021230460016me.fonepay.merchant0108DEV-MOCK0207{{jsonPath request.body '$.referenceLabel'}}5204000053034245405{{jsonPath request.body '$.amount'}}5802NP6304DEMO",
                                 "prn":"{{jsonPath request.body '$.referenceLabel'}}",
                                 "terminalId":"{{jsonPath request.body '$.terminalId'}}",
                                 "status":"Success"}
                                """)));

        // Default: pending. Flip to success via the admin API (see class javadoc).
        server.stubFor(post(urlEqualTo("/api/merchant/third-party/v2/thirdPartyDynamicQrGetStatus"))
                .willReturn(okJson("""
                        {"paymentStatus":"pending","paymentMessage":"Awaiting payment","fonepayTraceId":0}
                        """)));
    }
}
