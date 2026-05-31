// End-to-end in-browser proof of the FlexPop checkout loop.
// Drives the demo page in a real Chromium, flips the Fonepay mock mid-flow,
// and asserts the widget renders QR -> SETTLED off the VERIFIED gateway status
// (the source-of-truth invariant: success UI only after the poller confirms).
//
// Prerequisites (three processes, all local):
//   1. engine:  mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev,dev-fonepay-mock
//   2. widget:  npm run dev            (Vite on :5173, serves demo.html + the iframe)
//   3. chromium: npx playwright install chromium   (one-time)
// Then:  npm run e2e
//
// Exits non-zero on any page error, a stuck phase, or a missing onSettled callback.
import { chromium } from 'playwright';

const DEMO = 'http://localhost:5173/demo.html';
const MOCK_ADMIN = 'http://localhost:8089/__admin/mappings';
const SHOT = '/tmp/flexpop-proof';

const flipMockToSuccess = () =>
  fetch(MOCK_ADMIN, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      priority: 1,
      request: { method: 'POST', url: '/api/merchant/third-party/v2/thirdPartyDynamicQrGetStatus' },
      response: {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        jsonBody: { paymentStatus: 'success', paymentMessage: 'Payment success', fonepayTraceId: 99999 },
      },
    }),
  });

const log = (m) => console.log(`• ${m}`);

const browser = await chromium.launch();
const page = await browser.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

log('open demo page');
await page.goto(DEMO, { waitUntil: 'networkidle' });

log('click Boot — demo creates a real session via POST /v1/sessions, mounts widget iframe');
await page.click('#boot-btn');

const frame = await page.frameLocator('iframe[title="FlexPop Checkout"]');
await frame.locator('.fp-method').first().waitFor({ timeout: 15000 });
log('widget mounted, method picker rendered');

log('pick Fonepay');
await frame.locator('.fp-method', { hasText: 'Fonepay' }).click();

// Desktop routing -> QR panel. Wait for the QR panel's own copy (not the
// generic spinner, which also renders an SVG during the initiating phase).
await frame.getByText('Scan with your banking app', { exact: false }).waitFor({ timeout: 15000 });
await page.screenshot({ path: `${SHOT}-1-qr.png`, fullPage: true });
log('QR panel rendered (screenshot: -1-qr.png)');

log('flip Fonepay mock status -> success (simulates the customer paying)');
const r = await flipMockToSuccess();
if (!r.ok) throw new Error(`mock flip failed: ${r.status}`);

log('waiting for widget to reflect SETTLED (it polls the engine; poller settles within ~5s)...');
await frame.getByText('Payment received', { exact: false }).waitFor({ timeout: 30000 });
await page.screenshot({ path: `${SHOT}-2-settled.png`, fullPage: true });
log('SETTLED screen rendered in widget (screenshot: -2-settled.png)');

// The demo page's merchant-side event log must show the onSettled callback fired.
const logText = await page.locator('#events-log').textContent();
const sawSettled = /SETTLED/.test(logText || '');
log(`merchant onSettled callback fired: ${sawSettled ? 'YES' : 'NO'}`);

await browser.close();

console.log('\n=== merchant event log ===\n' + (logText || '').trim());
if (errors.length) {
  console.log('\n!! page errors:\n' + errors.join('\n'));
  process.exit(1);
}
if (!sawSettled) { console.log('\n!! onSettled never fired'); process.exit(1); }
console.log('\nPROOF COMPLETE: session -> txn -> QR -> verified-status -> SETTLED, end to end in the browser.');
