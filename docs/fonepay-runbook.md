# Fonepay Intent — integration runbook

How payment confirmation works for Fonepay today, why it's built this way, and
the one thing to change once Fonepay updates our merchant entitlement.

## TL;DR — the open action item

**Status-API polling is intentionally disabled.** Fonepay's
`thirdPartyDynamicQrGetStatus` returns `409 "Unauthorized request"` for our
production terminal `&lt;our production terminal PAN&gt;`, so the engine confirms payments from the
**real-time WebSocket** instead. When Fonepay enables the status API for this
terminal, re-enable polling as a second reconciliation safety net (see
[Re-enabling status polling](#re-enabling-status-polling)).

## The two Fonepay constraints

1. **No merchant webhook.** Fonepay's Intent API does not push HTTP callbacks.
   The only documented confirmation channels are the QR **WebSocket** and the
   **status API** (`Web Checkout Intent Flow For Merchant 1.10.pdf`, §9.5–9.6).
2. **Status API blocked for us.** `POST …/v2/thirdPartyDynamicQrGetStatus`
   returns `409 "Unauthorized request"` for terminal `&lt;our production terminal PAN&gt;` — even
   for a successfully-paid reference. Login, `generate-intent-qr`, and
   `banks/list` all work with the same credentials/signing, so this is a
   per-endpoint **entitlement** on Fonepay's side, not a signing bug. (A wrong
   `terminalId` returns `409 "Terminal detail not found"` instead — the PAN is
   correct.)

   To raise with Fonepay: *"Please enable thirdPartyDynamicQrGetStatus (Check QR
   Status) for terminal &lt;our production terminal PAN&gt; — login/QR-generation/bank-list work,
   status returns 409 Unauthorized request."*

## How confirmation works now (WebSocket-driven)

The widget shows the result instantly; the **engine** is the durable source of
truth, so a reloaded mobile tab still recovers the outcome.

- **Engine — `FonepaySocketManager`** (`…/engine/webhook/`): a scheduled sweep
  opens a `java.net.http` WebSocket to `wss://ws.fonepay.com/...` for every
  pending Fonepay txn, parses the `paymentSuccess` frame, and settles via
  `InboundWebhookService.processSynthetic` (same path the poller used).
  Reconnects on drop, re-attaches after restart, expires past-deadline txns.
  The per-txn socket URL is persisted on `transaction.websocket_url`
  (migration `V5`).
- **Widget** (`widget/src/App.tsx`): opens the same socket for instant
  feedback — `qrVerified` → "Confirm in your app", `paymentSuccess` →
  animated success/failure — and **also** polls the engine as the backstop.
- **Merchant demo** (`widget/src/demo.tsx`): persists the pending Fonepay txn on
  initiate and, on reload (the mobile bank-app round-trip can evict the tab),
  re-reads the engine and shows a "Payment complete/failed" banner.

### Real production frame shapes (differ from the doc)

```jsonc
// scan      — doc says " QRVerified"; production sends lowercase qrVerified
{"transactionStatus":"{\"success\":true,\"message\":\"VERIFIED\",\"qrVerified\":true}"}
// paid
{"transactionStatus":"{...,\"message\":\"RES000\",\"success\":true,\"paymentSuccess\":true}"}
```

`transactionStatus` is a JSON **string** (double-encoded). Result = inner
`paymentSuccess` boolean. Parsers: `FonepaySocketManager.parse` (engine) and
`parseFonepaySocketMessage` (widget) — keep them in sync.

## Config knobs (`flexpop.gateways.fonepay.*`)

| Property | Default (dev) | Meaning |
| --- | --- | --- |
| `socket-enabled` | `true` | Engine server-side WebSocket on/off (off in tests) |
| `socket-sweep-ms` | `3000` | How often the sweep ensures a socket per pending txn |
| `poll-interval-ms` | `86400000` | **Status poller — parked far out (disabled)** |
| `poll-initial-delay-ms` | `86400000` | Status poller initial delay (disabled) |

Env overrides: `FONEPAY_POLL_INTERVAL_MS`, `FONEPAY_POLL_INITIAL_DELAY_MS`, etc.
Secrets live in `engine.env` (gitignored); see `engine.env.example`.

## Re-enabling status polling

Once Fonepay confirms the status API is entitled for our terminal:

1. Verify with the script in [Checking status manually](#checking-status-manually)
   — expect a `paymentStatus` body, not `409`.
2. In `src/main/resources/application-dev.yml` (and prod env) set both back to
   ~5s:
   ```yaml
   poll-interval-ms: ${FONEPAY_POLL_INTERVAL_MS:5000}
   poll-initial-delay-ms: ${FONEPAY_POLL_INITIAL_DELAY_MS:3000}
   ```
   (or just set the env vars). Leave the WebSocket on — polling is the *second*
   safety net, not a replacement.

## Checking status manually

`scripts/`-style one-off (mints a fresh token + signs the body; reads creds from
`engine.env`/your shell):

```bash
set -a; . engine.env; set +a            # FONEPAY_USERNAME/PASSWORD/PRIVATE_KEY_B64/TERMINAL_ID/BASE_URL
BASE="${FONEPAY_BASE_URL%/}"
KEY=$(mktemp); { echo "-----BEGIN PRIVATE KEY-----"; echo "$FONEPAY_PRIVATE_KEY_B64" | fold -w64; echo "-----END PRIVATE KEY-----"; } >"$KEY"
sign(){ printf '%s' "$1" | openssl dgst -sha256 -sign "$KEY" | openssl base64 -A; }
LB="{\"username\":\"$FONEPAY_USERNAME\",\"password\":\"$FONEPAY_PASSWORD\"}"
TOKEN=$(curl -s -X POST "$BASE/api/merchant/third-party/v2/login" -H 'Content-Type: application/json' \
  -H "Authorization: Basic $(printf '%s' "$FONEPAY_USERNAME:$FONEPAY_PASSWORD" | openssl base64 -A)" \
  -H "signature: $(sign "$LB")" -d "$LB" | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
SB="{\"terminalId\":\"$FONEPAY_TERMINAL_ID\",\"referenceLabel\":\"<REF>\"}"   # <REF> = txn referenceLabel
curl -s -w $'\nHTTP %{http_code}\n' -X POST "$BASE/api/merchant/third-party/v2/thirdPartyDynamicQrGetStatus" \
  -H 'Content-Type: application/json' -H "Authorization: $TOKEN" -H "signature: $(sign "$SB")" -d "$SB"
rm -f "$KEY"
```

> Note: Fonepay allows roughly one active session per merchant — if the engine
> is running, its logins can revoke a standalone token (you'll see `401`/`409`).

## Endpoints (production base `https://thirdparty-merchantapi.fonepay.com`)

Note: production has **no** `/merchantThirdparty` path segment (the dev sandbox
`dev-external-gateway-new.fonepay.com/merchantThirdparty` does). All paths are
`/api/merchant/third-party/v2/...`: `login`, `generate-intent-qr`,
`banks/list`, `thirdPartyDynamicQrGetStatus`.

## Live test

1. Engine + widget dev server up; phone on the same Wi-Fi.
2. Phone → `http://<LAN-ip>:5173/demo.html` → **Pay with Fonepay** → pick a bank
   → approve in the bank app → return.
3. Engine log shows `Fonepay socket: <txn> -> SETTLED`; the page lands on
   "Payment complete" (recovered even if the tab reloaded).
