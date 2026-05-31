#!/usr/bin/env bash
#
# Fonepay Intent API — live sandbox smoke test.
#
# Exercises every *functional* request in the vendor Postman collection
# (reference-docs/Fonepay_Intent/Intent API collection.postman_collection.json)
# against the real dev gateway, signing each body with SHA256withRSA exactly the
# way the engine's FonepaySigner does. Prints PASS/FAIL per endpoint and exits
# non-zero if any check fails — so it doubles as a CI/pre-flight gate.
#
# This is a check of the *upstream gateway contract*, not the FlexPop engine.
# (The engine's own /v1 API is covered by `mvn test` + widget/prove-*.mjs.)
#
# Credentials: by default read straight from the Postman collection (which embeds
# them). Override any of these via env vars — and prefer doing so once those
# committed creds are rotated:
#     FONEPAY_USERNAME  FONEPAY_PASSWORD  FONEPAY_PRIVATE_KEY_B64
#     FONEPAY_BASE_URL  FONEPAY_TERMINAL_ID
#
# Usage:   ./scripts/fonepay-sandbox-smoke.sh
# Deps:    bash, curl, openssl, python3   (all stock on macOS/Linux)
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COLLECTION="$REPO_ROOT/reference-docs/Fonepay_Intent/Intent API collection.postman_collection.json"
BASE_PATH="/api/merchant/third-party/v2"

# ---- colours (disabled if not a TTY) ----------------------------------------
if [[ -t 1 ]]; then RED=$'\e[31m'; GRN=$'\e[32m'; YEL=$'\e[33m'; DIM=$'\e[2m'; RST=$'\e[0m'
else RED=; GRN=; YEL=; DIM=; RST=; fi

PASS=0; FAIL=0; SKIP=0
ok()   { echo "  ${GRN}PASS${RST} $*"; PASS=$((PASS+1)); }
bad()  { echo "  ${RED}FAIL${RST} $*"; FAIL=$((FAIL+1)); }
skip() { echo "  ${YEL}SKIP${RST} $*"; SKIP=$((SKIP+1)); }
hdr()  { echo; echo "${DIM}=== $* ===${RST}"; }

# ---- load credentials -------------------------------------------------------
load_from_collection() {  # field -> stdout
  python3 - "$COLLECTION" "$1" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
v = {x["key"]: x.get("value") for x in d.get("variable", [])}
print(v.get(sys.argv[2], ""))
PY
}

if [[ ! -f "$COLLECTION" ]]; then
  echo "${RED}Cannot find Postman collection at:${RST} $COLLECTION"; exit 2
fi

USERNAME="${FONEPAY_USERNAME:-$(load_from_collection username)}"
PASSWORD="${FONEPAY_PASSWORD:-$(load_from_collection password)}"
PRIVKEY_B64="${FONEPAY_PRIVATE_KEY_B64:-$(load_from_collection privateKey)}"
BASE_URL="${FONEPAY_BASE_URL:-$(load_from_collection baseUrl)}"
# The collection's terminalId lives in the Generate Intent QR request body.
TERMINAL_ID="${FONEPAY_TERMINAL_ID:-$(python3 - "$COLLECTION" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
def find(items):
    for it in items:
        if "item" in it:
            r = find(it["item"])
            if r: return r
        elif it.get("name") == "Generate Intent QR":
            return json.loads(it["request"]["body"]["raw"]).get("terminalId")
    return None
print(find(d["item"]) or "")
PY
)}"

if [[ -z "$USERNAME" || -z "$PASSWORD" || -z "$PRIVKEY_B64" || -z "$BASE_URL" || -z "$TERMINAL_ID" ]]; then
  echo "${RED}Missing credentials.${RST} Set FONEPAY_* env vars or check the collection."; exit 2
fi

echo "Target : $BASE_URL$BASE_PATH"
echo "User   : $USERNAME    Terminal: $TERMINAL_ID"

# ---- signing key (PKCS8 base64 -> PEM) --------------------------------------
KEY_PEM="$(mktemp -t fp_key.XXXXXX.pem)"
trap 'rm -f "$KEY_PEM"' EXIT
{ echo "-----BEGIN PRIVATE KEY-----"; echo "$PRIVKEY_B64" | fold -w 64; echo "-----END PRIVATE KEY-----"; } > "$KEY_PEM"
if ! openssl pkey -in "$KEY_PEM" -noout 2>/dev/null; then
  echo "${RED}Private key failed to parse as PKCS8.${RST}"; exit 2
fi
# SHA256withRSA(body) -> base64, matching FonepaySigner. GET signs the empty string.
sign() { printf '%s' "${1:-}" | openssl dgst -sha256 -sign "$KEY_PEM" | openssl base64 -A; }

# jget <json> <python-expr on d> ; prints result or empty on error
jget() { printf '%s' "$1" | python3 -c "import sys,json
try:
    d=json.load(sys.stdin); print($2)
except Exception: print('')" 2>/dev/null; }

TOKEN=""; PRN=""

# ---- 1) LOGIN ---------------------------------------------------------------
hdr "1) Login  POST $BASE_PATH/login"
LOGIN_BODY="{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}"
BASIC=$(printf '%s' "$USERNAME:$PASSWORD" | openssl base64 -A)
RESP=$(curl -s -w $'\n%{http_code}' -X POST "$BASE_URL$BASE_PATH/login" \
  -H 'Content-Type: application/json' -H "Authorization: Basic $BASIC" -H "signature: $(sign "$LOGIN_BODY")" \
  -d "$LOGIN_BODY")
CODE=${RESP##*$'\n'}; JSON=${RESP%$'\n'*}
TOKEN=$(jget "$JSON" 'd["accessToken"]')
if [[ "$CODE" == "200" && -n "$TOKEN" ]]; then
  ok "login 200, accessToken present (${TOKEN:0:10}…)"
  [[ "$TOKEN" == Bearer\ * ]] && echo "       ${DIM}note: token already carries the \"Bearer \" prefix${RST}"
else
  bad "login HTTP $CODE — $(jget "$JSON" 'd.get("message","")')"
  echo "${RED}Cannot continue without a token.${RST}"; echo
  echo "Summary: ${GRN}$PASS passed${RST}, ${RED}$FAIL failed${RST}, ${YEL}$SKIP skipped${RST}"; exit 1
fi

# Authed calls send the token verbatim (it already includes "Bearer ").
AUTH=(-H "Authorization: $TOKEN")

# ---- 2) GENERATE INTENT QR --------------------------------------------------
hdr "2) Generate Intent QR  POST $BASE_PATH/generate-intent-qr"
REF="SMOKE$(date +%H%M%S)"
QR_BODY="{\"amount\":100.00,\"billId\":\"$REF\",\"terminalId\":\"$TERMINAL_ID\",\"paymentMode\":\"QR\",\"referenceLabel\":\"$REF\",\"qrType\":\"INTENT_QR\"}"
RESP=$(curl -s -w $'\n%{http_code}' -X POST "$BASE_URL$BASE_PATH/generate-intent-qr" \
  -H 'Content-Type: application/json' "${AUTH[@]}" -H "signature: $(sign "$QR_BODY")" -d "$QR_BODY")
CODE=${RESP##*$'\n'}; JSON=${RESP%$'\n'*}
QRSTR=$(jget "$JSON" 'd["qrString"]'); PRN=$(jget "$JSON" 'd.get("prn","")')
if [[ "$CODE" =~ ^20[0-9]$ && "$QRSTR" == 0002* ]]; then
  ok "HTTP $CODE, qrString is a valid EMVCo QR (prn=$PRN)"
  echo "       ${DIM}qrString: ${QRSTR:0:48}…  crc=${QRSTR: -4}${RST}"
else
  bad "HTTP $CODE — qrString=${QRSTR:0:20}"
fi

# ---- 3) FETCH BANKS LIST ----------------------------------------------------
hdr "3) Fetch Banks List  GET $BASE_PATH/banks/list"
RESP=$(curl -s -w $'\n%{http_code}' "$BASE_URL$BASE_PATH/banks/list" \
  "${AUTH[@]}" -H "signature: $(sign '')" -H "mobileNo: 9841234567" -H "paymentMode: INTENT")
CODE=${RESP##*$'\n'}; JSON=${RESP%$'\n'*}
NBANKS=$(jget "$JSON" 'len(d.get("bankDetails",[]))')
if [[ "$CODE" == "200" && -n "$NBANKS" && "$NBANKS" -gt 0 ]]; then
  ok "HTTP 200, bankDetails[] has $NBANKS banks"
  echo "       ${DIM}$(jget "$JSON" '", ".join(b.get("bankName","?")+" ("+b.get("intentScheme","")+")" for b in d["bankDetails"][:3])')…${RST}"
else
  bad "HTTP $CODE — bankDetails count=$NBANKS (engine expects key 'bankDetails')"
fi

# ---- 4) QR PAYMENT STATUS ---------------------------------------------------
hdr "4) Qr Payment Status  POST $BASE_PATH/thirdPartyDynamicQrGetStatus"
if [[ -z "$PRN" ]]; then PRN="$REF"; fi
ST_BODY="{\"terminalId\":\"$TERMINAL_ID\",\"referenceLabel\":\"$PRN\"}"
RESP=$(curl -s -w $'\n%{http_code}' -X POST "$BASE_URL$BASE_PATH/thirdPartyDynamicQrGetStatus" \
  -H 'Content-Type: application/json' "${AUTH[@]}" -H "signature: $(sign "$ST_BODY")" -d "$ST_BODY")
CODE=${RESP##*$'\n'}; JSON=${RESP%$'\n'*}
PSTATUS=$(jget "$JSON" 'd.get("paymentStatus","")')
if [[ "$CODE" == "200" && -n "$PSTATUS" ]]; then
  ok "HTTP 200, paymentStatus=\"$PSTATUS\"  (fresh/unpaid QR → not 'success' yet, as expected)"
  # The engine only maps success->SETTLED, failed->FAILED; anything else->PENDING.
  case "$PSTATUS" in
    success|failed) : ;;
    *) echo "       ${YEL}heads-up:${RST} engine maps \"$PSTATUS\" to PENDING — confirm a terminal status (e.g. timeout) shouldn't expire the txn" ;;
  esac
else
  bad "HTTP $CODE — paymentStatus missing (engine reads paymentStatus/paymentMessage/fonepayTraceId)"
fi

# ---- websocQRGenerate (placeholder) -----------------------------------------
hdr "5) websocQRGenerate"
skip "no URL defined in the collection — unfinished placeholder, nothing to call"

# ---- summary ----------------------------------------------------------------
echo
echo "Summary: ${GRN}$PASS passed${RST}, ${RED}$FAIL failed${RST}, ${YEL}$SKIP skipped${RST}"
[[ "$FAIL" -eq 0 ]] && exit 0 || exit 1
