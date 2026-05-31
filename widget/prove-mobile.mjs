// Render the MOBILE app-intent flow (bank picker with per-bank deep-links)
// against the real Fonepay sandbox. Creates a MOBILE-routed session (the demo
// page hard-codes desktop), pastes it into the demo, boots, picks Fonepay, and
// asserts the BankPicker renders real banks with intentScheme deep-links.
import { chromium, devices } from 'playwright';

const SK = 'sk_dev_local_FLEXPOPDEVKEY1234567890';
const ENGINE = 'http://localhost:8080';

// 1) Create a MOBILE session server-side (Sec-CH-UA-Mobile: ?1 → device=MOBILE).
const sessRes = await fetch(`${ENGINE}/v1/sessions`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${SK}`,
    'Sec-CH-UA-Mobile': '?1',
    'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Pixel 7)',
  },
  body: JSON.stringify({ amount: 3050000, currency: 'NPR', country: 'NP', reference: 'MOBILE-BROWSER' }),
});
const session = await sessRes.json();
if (session.device !== 'MOBILE') throw new Error(`expected MOBILE, got ${session.device}`);
console.log('• mobile session created:', session.session_id, 'device=', session.device);

// 2) Drive the demo in a phone-sized context.
const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['Pixel 7'] });
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
await page.goto('http://localhost:5173/demo.html', { waitUntil: 'networkidle' });

// Paste the mobile session into the textarea so the demo uses it verbatim.
await page.fill('#session-json', JSON.stringify(session));
await page.click('#boot-btn');

const frame = page.frameLocator('iframe[title="FlexPop Checkout"]');
await frame.locator('.fp-method', { hasText: 'Fonepay' }).waitFor({ timeout: 15000 });
await frame.locator('.fp-method', { hasText: 'Fonepay' }).click();

// Mobile + intents → BankPicker ("Pick your banking app"), not the QR panel.
await frame.getByText('Pick your banking app', { exact: false }).waitFor({ timeout: 20000 });
await page.waitForTimeout(400);

const banks = await frame.locator('.fp-method-name').allTextContents();
const firstHref = await frame.locator('a.fp-method').first().getAttribute('href');
await page.screenshot({ path: '/tmp/flexpop-mobile.png', fullPage: true });

console.log('• bank picker rendered with banks:', banks.join(', '));
console.log('• first deep-link href:', firstHref);
await browser.close();
if (errors.length) { console.log('page errors:\n' + errors.join('\n')); process.exit(1); }
if (!banks.length || !firstHref) { console.log('!! no bank intents rendered'); process.exit(1); }
console.log('OK — real-sandbox mobile bank-intent picker rendered in the browser.');
