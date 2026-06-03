package com.flxpop.engine.adapter.esewa;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flxpop.engine.adapter.GatewayAdapter.StatusResult;
import com.flxpop.engine.adapter.esewa.EsewaAdapter.CheckoutForm;
import com.flxpop.engine.domain.Gateway;
import com.flxpop.engine.domain.TxnStatus;
import com.flxpop.engine.domain.entity.TransactionEntity;
import com.flxpop.engine.domain.repo.TransactionRepository;
import com.flxpop.engine.webhook.InboundWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Browser-facing endpoints for the eSewa redirect flow. These are NOT API-key
 * authenticated (they're hit by the shopper's browser and by eSewa's redirect)
 * — {@code /v1/gateways/**} is whitelisted in {@code ApiKeyAuthFilter}. Trust
 * comes from verifying eSewa's HMAC signature on the success callback, not from
 * an API key.
 *
 * <ul>
 *   <li>{@code GET /checkout/{uuid}} — renders the signed, auto-submitting form
 *       POST to eSewa's hosted payment page.</li>
 *   <li>{@code GET /callback/{uuid}/success} — eSewa redirects here with a
 *       Base64 {@code data} blob; we verify its signature and settle the txn.</li>
 *   <li>{@code GET /callback/{uuid}/failure} — eSewa's failure/pending landing;
 *       we reconcile with an authoritative status check rather than guessing.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/gateways/esewa")
public class EsewaCheckoutController {

    private static final Logger log = LoggerFactory.getLogger(EsewaCheckoutController.class);

    // eSewa echoes total_amount as a JSON number; reading floats as BigDecimal
    // preserves the exact textual form (e.g. "1000.0") it signed, so our
    // verification message matches byte-for-byte.
    private static final ObjectMapper CALLBACK_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final EsewaProperties props;
    private final EsewaAdapter adapter;
    private final EsewaSigner signer;
    private final TransactionRepository txnRepo;
    private final InboundWebhookService inboundService;

    public EsewaCheckoutController(EsewaProperties props,
                                   EsewaAdapter adapter,
                                   EsewaSigner signer,
                                   TransactionRepository txnRepo,
                                   InboundWebhookService inboundService) {
        this.props = props;
        this.adapter = adapter;
        this.signer = signer;
        this.txnRepo = txnRepo;
        this.inboundService = inboundService;
    }

    @GetMapping(value = "/checkout/{uuid}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> checkout(@PathVariable("uuid") String uuid) {
        TransactionEntity txn = txnRepo.findByPublicId(uuid).orElse(null);
        if (txn == null || txn.getGateway() != Gateway.ESEWA) {
            return html(HttpStatus.NOT_FOUND,
                    resultPage("Payment not found", "We couldn't find that payment.", Tone.MUTED));
        }
        if (txn.getStatus().isTerminal()) {
            return html(HttpStatus.OK, resultPageFor(txn));
        }
        String totalAmount = adapter.totalAmount(txn.getAmountMinor(), txn.getCurrency());
        String callbackBase = props.engineBaseUrl() + "/v1/gateways/esewa/callback/" + uuid;
        CheckoutForm form = adapter.buildCheckoutForm(
                uuid, totalAmount, callbackBase + "/success", callbackBase + "/failure");
        return html(HttpStatus.OK, autoSubmitForm(form));
    }

    @GetMapping(value = "/callback/{uuid}/success", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> success(@PathVariable("uuid") String uuid,
                                          @RequestParam(value = "data", required = false) String data) {
        TransactionEntity txn = txnRepo.findByPublicId(uuid).orElse(null);
        if (txn == null) {
            return html(HttpStatus.NOT_FOUND,
                    resultPage("Payment not found", "We couldn't find that payment.", Tone.MUTED));
        }

        JsonNode body = decode(data);
        if (body != null && verifiedComplete(body, uuid)) {
            inboundService.processSynthetic(txn,
                    new StatusResult(uuid, "SETTLED", Instant.now(), null, null));
        } else {
            // Missing/invalid signature or non-COMPLETE status — never trust the
            // redirect blindly; ask eSewa directly.
            log.warn("eSewa success callback for {} not verifiable — reconciling via status check", uuid);
            reconcile(txn);
        }
        return landing(reload(txn));
    }

    @GetMapping(value = "/callback/{uuid}/failure", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> failure(@PathVariable("uuid") String uuid,
                                          @RequestParam(value = "data", required = false) String data) {
        TransactionEntity txn = txnRepo.findByPublicId(uuid).orElse(null);
        if (txn == null) {
            return html(HttpStatus.NOT_FOUND,
                    resultPage("Payment not found", "We couldn't find that payment.", Tone.MUTED));
        }
        // eSewa lands here for FAILURE *or* PENDING — let an authoritative status
        // check decide, instead of force-failing a payment that may still settle.
        reconcile(txn);
        return landing(reload(txn));
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Send the shopper's browser back to the merchant checkout (the full-page
     * redirect pattern eSewa's flow is built around) with the outcome in the
     * query string, so the checkout can show the result. If no return URL is
     * configured, fall back to the engine's own result page.
     */
    private ResponseEntity<String> landing(TransactionEntity txn) {
        String returnUrl = props.returnUrl();
        if (returnUrl == null || returnUrl.isBlank()) {
            return html(HttpStatus.OK, resultPageFor(txn));
        }
        String result = switch (txn.getStatus()) {
            case SETTLED -> "success";
            case FAILED  -> "failed";
            case EXPIRED -> "expired";
            default      -> "pending";
        };
        String sep = returnUrl.contains("?") ? "&" : "?";
        String location = returnUrl + sep
                + "flxpop_txn=" + URLEncoder.encode(txn.getPublicId(), StandardCharsets.UTF_8)
                + "&flxpop_result=" + result;
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    private void reconcile(TransactionEntity txn) {
        StatusResult res = adapter.checkStatus(
                txn.getGatewayRef(), adapter.totalAmount(txn.getAmountMinor(), txn.getCurrency()));
        inboundService.processSynthetic(txn, res);
    }

    private TransactionEntity reload(TransactionEntity txn) {
        return txnRepo.findByPublicId(txn.getPublicId()).orElse(txn);
    }

    private JsonNode decode(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            byte[] json = Base64.getDecoder().decode(data);
            return CALLBACK_MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("eSewa callback: undecodable data blob: {}", e.getMessage());
            return null;
        }
    }

    /** Verify eSewa's signature over its signed_field_names, then check it's a COMPLETE for this uuid. */
    private boolean verifiedComplete(JsonNode body, String uuid) {
        String signedFieldNames = text(body, "signed_field_names");
        String signature = text(body, "signature");
        if (signedFieldNames == null || signature == null) return false;

        StringBuilder message = new StringBuilder();
        for (String field : signedFieldNames.split(",")) {
            if (message.length() > 0) message.append(',');
            message.append(field).append('=').append(text(body, field));
        }
        if (!signer.verify(message.toString(), signature)) {
            log.warn("eSewa callback signature mismatch for {}", uuid);
            return false;
        }
        return "COMPLETE".equalsIgnoreCase(text(body, "status"))
                && uuid.equals(text(body, "transaction_uuid"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null ? null : v.asText();
    }

    private ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
    }

    private String autoSubmitForm(CheckoutForm form) {
        StringBuilder inputs = new StringBuilder();
        for (Map.Entry<String, String> e : form.fields().entrySet()) {
            inputs.append("<input type=\"hidden\" name=\"").append(esc(e.getKey()))
                    .append("\" value=\"").append(esc(e.getValue())).append("\">\n");
        }
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Redirecting to eSewa…</title>
                <style>
                  body{font-family:-apple-system,BlinkMacSystemFont,system-ui,sans-serif;background:#f4f5f8;
                       color:#23262f;display:grid;place-items:center;min-height:100vh;margin:0}
                  .box{text-align:center}
                  .spin{width:34px;height:34px;border-radius:50%%;border:3px solid #e6e9f0;border-top-color:#5DBB46;
                        animation:s .8s linear infinite;margin:0 auto 16px}
                  @keyframes s{to{transform:rotate(360deg)}}
                  button{margin-top:14px;padding:12px 20px;border:0;border-radius:10px;background:#5DBB46;color:#fff;
                         font:600 15px system-ui;cursor:pointer}
                </style></head>
                <body onload="document.forms[0].submit()">
                  <div class="box">
                    <div class="spin"></div>
                    <div>Redirecting you to eSewa…</div>
                    <form action="%s" method="POST">
                      %s
                      <noscript><button type="submit">Continue to eSewa</button></noscript>
                    </form>
                  </div>
                </body></html>
                """.formatted(esc(form.action()), inputs.toString());
    }

    private enum Tone { SUCCESS, FAIL, MUTED }

    private String resultPageFor(TransactionEntity txn) {
        return switch (txn.getStatus()) {
            case SETTLED -> resultPage("Payment complete",
                    "Your eSewa payment was received. You can return to the checkout tab.", Tone.SUCCESS);
            case FAILED -> resultPage("Payment failed",
                    "This eSewa payment did not go through. You can return to the checkout and try again.", Tone.FAIL);
            case EXPIRED -> resultPage("Payment expired",
                    "The payment window closed. Please start a new checkout.", Tone.FAIL);
            default -> resultPage("Payment processing",
                    "We're confirming your payment with eSewa. You can return to the checkout tab — "
                            + "it updates automatically.", Tone.MUTED);
        };
    }

    private String resultPage(String title, String message, Tone tone) {
        String color = switch (tone) {
            case SUCCESS -> "#16A34A";
            case FAIL -> "#DC2626";
            case MUTED -> "#5C6B86";
        };
        String mark = switch (tone) {
            case SUCCESS -> "&#10003;";
            case FAIL -> "&#10005;";
            case MUTED -> "&#8230;";
        };
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                  body{font-family:-apple-system,BlinkMacSystemFont,system-ui,sans-serif;background:#f4f5f8;
                       color:#23262f;display:grid;place-items:center;min-height:100vh;margin:0}
                  .card{background:#fff;border:1px solid #e6e9f0;border-radius:16px;padding:34px 38px;max-width:380px;
                        text-align:center;box-shadow:0 18px 50px -16px rgba(20,28,48,.22)}
                  .ic{width:54px;height:54px;border-radius:50%%;display:grid;place-items:center;margin:0 auto 16px;
                      font-size:26px;color:#fff;background:%s}
                  h1{font-size:20px;margin:0 0 8px}
                  p{color:#5c6b86;font-size:14px;line-height:1.55;margin:0}
                </style></head>
                <body><div class="card"><div class="ic">%s</div><h1>%s</h1><p>%s</p></div></body></html>
                """.formatted(esc(title), color, mark, esc(title), esc(message));
    }

    /** Minimal HTML escaping for attribute/text contexts. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
