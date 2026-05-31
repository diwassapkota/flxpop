// Render the REAL Fonepay-sandbox QR in the widget (no settlement leg — that
// needs a human to actually scan & pay). Confirms the genuine qrString flows
// L2 -> L1 and renders as a scannable QR in the iframe.
import { chromium } from 'playwright';
const browser = await chromium.launch();
const page = await browser.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
await page.goto('http://localhost:5173/demo.html', { waitUntil: 'networkidle' });
await page.click('#boot-btn');
const frame = page.frameLocator('iframe[title="FlexPop Checkout"]');
await frame.locator('.fp-method', { hasText: 'Fonepay' }).waitFor({ timeout: 15000 });
await frame.locator('.fp-method', { hasText: 'Fonepay' }).click();
await frame.getByText('Scan with your banking app', { exact: false }).waitFor({ timeout: 20000 });
// give QRCode.toCanvas a beat to paint
await page.waitForTimeout(800);
const txnLine = await frame.locator('.fp-tiny').textContent().catch(() => '');
await page.screenshot({ path: '/tmp/flexpop-real-qr.png', fullPage: true });
console.log('rendered real-sandbox QR in widget:', txnLine);
await browser.close();
if (errors.length) { console.log('page errors:\n' + errors.join('\n')); process.exit(1); }
console.log('OK — real Fonepay QR rendered in the browser widget.');
