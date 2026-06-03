// Air Arabia checkout demo page — merchant side.
//
// Methods: Cards, Apple Pay (faithful visual replicas that stay on the
// merchant's existing gateway — no form collected here), and the local wallets
// Fonepay + eSewa, surfaced because the shopper's location resolves to Nepal.
// The local tiles show the brands the customer already trusts and deep-link
// straight into that gateway — each boots the real FlxPop widget with a
// preselected gateway, skipping the in-widget method picker. FlxPop is the
// invisible rail behind them (a small "powered by" line keeps the attribution).
export {};

declare global {
  interface Window {
    FlexPop: {
      mount: (opts: Record<string, unknown>) => { destroy: () => void };
    };
  }
}

type Method = 'card' | 'applepay' | 'fonepay' | 'esewa';
type LocalMethod = 'fonepay' | 'esewa';

// Derive the engine origin from whatever host serves the page, so it works the
// same on localhost AND from a phone on the LAN (http://<lan-ip>:5173 → :8080).
const ENGINE_BASE_URL = (window as unknown as { __ENGINE?: string }).__ENGINE
  ?? `${window.location.protocol}//${window.location.hostname}:8080`;
const WIDGET_ORIGIN   = window.location.origin;
const PUBLISHABLE_KEY  = 'pk_dev_local_FLEXPOPPUBLICKEY1234567890';
const DEMO_SECRET_KEY  = 'sk_dev_local_FLEXPOPDEVKEY1234567890'; // demo only — sk_* never goes to a real browser

// NPR 13 fare ≈ AED 0.31 (13.00 → 1300 minor units).
const FARE_NPR_MINOR = 1300;

const GATEWAY: Record<LocalMethod, string> = { fonepay: 'FONEPAY', esewa: 'ESEWA' };

// Per-method footer copy. Cards/Apple Pay keep the reference's AED-first
// framing; the local wallets flip the headline price to NPR (charged locally).
const FOOTER: Record<Method, { main: string; sub: string; label: string }> = {
  card:     { main: 'AED 0.31',  sub: 'Equivalent to NPR 13',           label: 'Continue to Payment' },
  applepay: { main: 'AED 0.31',  sub: 'Equivalent to NPR 13',           label: 'Continue with Apple Pay' },
  fonepay:  { main: 'NPR 13', sub: '≈ AED 0.31 · paid locally in Nepal', label: 'Pay with Fonepay' },
  esewa:    { main: 'NPR 13', sub: '≈ AED 0.31 · paid locally in Nepal', label: 'Pay with eSewa' },
};

const $  = <T extends Element>(sel: string) => document.querySelector(sel) as T | null;
const $$ = (sel: string) => Array.from(document.querySelectorAll(sel));

const isLocal = (m: Method): m is LocalMethod => m === 'fonepay' || m === 'esewa';

let selected: Method = 'fonepay';
let mounted: { destroy: () => void } | null = null;
let bootedMethod: LocalMethod | null = null;

function logEvent(message: string) {
  const log = $('#events-log');
  if (!log) return;
  const ts = new Date().toLocaleTimeString();
  log.textContent = `[${ts}] ${message}\n` + (log.textContent || '');
}

/** Tear down a mounted widget and restore the value-prop teaser. */
function resetLive() {
  if (mounted) { mounted.destroy(); mounted = null; }
  bootedMethod = null;
  const teaser = $<HTMLElement>('#fp-teaser');
  if (teaser) teaser.style.display = '';
  $('#fp-live')?.classList.remove('is-on');
  const log = $('#events-log');
  if (log) log.textContent = '';
}

/**
 * Clear the post-payment UI (the eSewa return banner + the disabled/green
 * "Paid" button) so the checkout is payable again after a method switch.
 * Without this, returning from a completed eSewa payment leaves the Continue
 * button disabled — every other method's button then looks dead.
 */
function clearReturnState() {
  const ret = $<HTMLElement>('#fp-return');
  // Drop the tone class (which is what makes it display) + empty it.
  if (ret) { ret.className = 'fp-return'; ret.hidden = true; ret.replaceChildren(); }
  const btn = $<HTMLButtonElement>('#aa-continue');
  if (btn) { btn.disabled = false; btn.style.display = ''; btn.style.background = ''; btn.style.boxShadow = ''; }
  // Restore the value-prop teaser if no live widget is mounted.
  if (!mounted) {
    const teaser = $<HTMLElement>('#fp-teaser');
    if (teaser) teaser.style.display = '';
    $('#fp-live')?.classList.remove('is-on');
  }
}

function selectMethod(method: Method) {
  // Switching away from a live gateway (or to the other wallet) resets the widget.
  if (bootedMethod && bootedMethod !== method) resetLive();
  clearReturnState(); // back to a clean, payable state on every method switch
  selected = method;

  $$('.aa-method').forEach((btn) => {
    btn.classList.toggle('is-selected', (btn as HTMLElement).dataset.method === method);
  });
  // Fonepay and eSewa share one value panel (data-panel="local").
  const panel = isLocal(method) ? 'local' : method;
  $$('.aa-panel').forEach((p) => {
    p.classList.toggle('is-active', (p as HTMLElement).dataset.panel === panel);
  });

  const foot = FOOTER[method];
  const main = $('#amt-main'); if (main) main.textContent = foot.main;
  const sub  = $('#amt-sub');  if (sub)  sub.textContent  = foot.sub;
  const label = $('#continue-label'); if (label) label.textContent = foot.label;
}

async function ensureLoader(): Promise<void> {
  if (window.FlexPop) return;
  await new Promise<void>((resolve, reject) => {
    const s = document.createElement('script');
    s.src = '/flexpop.js';
    s.onload  = () => resolve();
    s.onerror = () => reject(new Error('Failed to load flexpop.js'));
    document.head.appendChild(s);
  });
}

async function createDemoSession(): Promise<unknown> {
  const res = await fetch(`${ENGINE_BASE_URL}/v1/sessions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${DEMO_SECRET_KEY}`,
      // No forced device hint — the engine's DeviceResolver detects desktop vs
      // mobile from the User-Agent: desktop → scannable QR, phone → bank picker
      // with native app deep-links.
    },
    body: JSON.stringify({
      amount: FARE_NPR_MINOR,
      currency: 'NPR',
      country: 'NP',           // ← the location signal that surfaces Fonepay + eSewa
      reference: 'AIRARABIA-NP-DEMO',
    }),
  });
  if (!res.ok) {
    throw new Error(`Engine returned ${res.status} — is mvn spring-boot:run up at ${ENGINE_BASE_URL}?`);
  }
  return res.json();
}

/** Boot the real widget straight into one gateway — no in-widget method picker. */
async function bootLocal(method: LocalMethod) {
  const teaser = $<HTMLElement>('#fp-teaser');
  if (teaser) teaser.style.display = 'none';
  $('#fp-live')?.classList.add('is-on');

  const btn = $<HTMLButtonElement>('#aa-continue');
  const setLabel = (t: string) => { const l = $('#continue-label'); if (l) l.textContent = t; };
  if (btn) { btn.disabled = true; setLabel('Starting…'); }

  let session: unknown;
  try {
    logEvent(`→ creating NPR session, deep-linking ${GATEWAY[method]}…`);
    session = await createDemoSession();
    logEvent('✓ session created — booting widget');
  } catch (err) {
    logEvent(`✗ ${(err as Error).message}`);
    if (btn) { btn.disabled = false; setLabel(FOOTER[method].label); }
    return;
  }

  await ensureLoader();
  if (mounted) { mounted.destroy(); mounted = null; }

  mounted = window.FlexPop.mount({
    container: '#checkout',
    widgetOrigin: WIDGET_ORIGIN,
    engineBaseUrl: ENGINE_BASE_URL,
    publishableKey: PUBLISHABLE_KEY,
    session,
    gateway: GATEWAY[method], // ← skip the picker, go straight to this wallet
    onInitiated: (e: Record<string, unknown>) => logEvent(`→ initiated  txn_id=${e.txn_id} status=${e.status}`),
    onSettled:   (e: Record<string, unknown>) => logEvent(`✓ SETTLED    txn_id=${e.txn_id}`),
    onFailed:    (e: Record<string, unknown>) => logEvent(`✗ FAILED     txn_id=${e.txn_id}`),
    onExpired:   (e: Record<string, unknown>) => logEvent(`! EXPIRED    txn_id=${e.txn_id}`),
  });

  bootedMethod = method;
  // The action is now inside the widget (scan the QR / go to eSewa), so the
  // footer button would be a dead duplicate — hide it. It's restored on a
  // method switch via clearReturnState().
  if (btn) btn.style.display = 'none';
  // On mobile the widget mounts below the fold — bring it into view.
  if (window.innerWidth <= 860) {
    $('#checkout')?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

function onContinue() {
  if (isLocal(selected)) {
    if (bootedMethod !== selected) bootLocal(selected);
    return;
  }
  // Cards / Apple Pay are not processed here — nudge to a local wallet.
  selectMethod('fonepay');
  $('.aa-localgroup')?.animate(
    [{ transform: 'translateX(0)' }, { transform: 'translateX(-4px)' }, { transform: 'translateX(4px)' }, { transform: 'translateX(0)' }],
    { duration: 260, easing: 'ease-in-out' },
  );
}

function showReturn(kind: 'ok' | 'bad' | 'wait', title: string, msg: string, ref: string) {
  const el = $('#fp-return');
  if (!el) return;
  el.className = 'fp-return ' + kind;
  el.replaceChildren(); // clear; build with textContent so the txn ref can't inject HTML

  const mark = document.createElement('div');
  mark.className = 'rmark';
  mark.textContent = kind === 'ok' ? '✓' : kind === 'bad' ? '✕' : '…';

  const body = document.createElement('div');
  const h = document.createElement('h3'); h.textContent = title;
  const p = document.createElement('p'); p.textContent = msg;
  const r = document.createElement('div'); r.className = 'ref'; r.textContent = ref;
  body.append(h, p, r);

  el.append(mark, body);
  (el as HTMLElement).hidden = false;
}

// eSewa's signed callback 302s the shopper back here with the outcome. Show it,
// verified against the engine rather than trusting the redirect param alone.
async function handleEsewaReturn() {
  const params = new URLSearchParams(window.location.search);
  const txnId = params.get('flxpop_txn');
  const result = params.get('flxpop_result');
  if (!txnId || !result) return;

  // Strip the params so a refresh resets the demo to a clean checkout.
  window.history.replaceState({}, '', window.location.pathname);

  selectMethod('esewa'); // returns are always from the eSewa redirect
  const teaser = $<HTMLElement>('#fp-teaser');
  if (teaser) teaser.style.display = 'none';

  let status = result.toUpperCase();
  try {
    const res = await fetch(`${ENGINE_BASE_URL}/v1/transactions/${txnId}`, {
      headers: { Authorization: `Bearer ${PUBLISHABLE_KEY}` },
    });
    if (res.ok) status = String((await res.json()).status ?? status).toUpperCase();
  } catch {
    /* engine unreachable — fall back to the redirect param */
  }

  const btn = $<HTMLButtonElement>('#aa-continue');
  const label = $('#continue-label');
  if (status === 'SETTLED' || result === 'success') {
    showReturn('ok', 'Payment complete',
      'Your eSewa payment was received and verified with the FlxPop engine.', txnId);
    if (btn) { btn.disabled = true; btn.style.background = '#16A34A'; btn.style.boxShadow = 'none'; }
    if (label) label.textContent = 'Paid with eSewa ✓';
  } else if (status === 'FAILED' || status === 'EXPIRED' || result === 'failed' || result === 'expired') {
    showReturn('bad', 'Payment not completed',
      'Your eSewa payment didn’t go through. Pick a method and try again.', txnId);
  } else {
    showReturn('wait', 'Payment processing',
      'eSewa is still confirming this payment — it’ll update shortly.', txnId);
  }
}

function wire() {
  $$('.aa-method').forEach((btn) => {
    btn.addEventListener('click', () => selectMethod((btn as HTMLElement).dataset.method as Method));
  });
  // "Try paying in NPR…" nudges inside the card / apple pay panels
  $$('[data-goto]').forEach((el) => {
    el.addEventListener('click', () => selectMethod((el as HTMLElement).dataset.goto as Method));
  });
  $('#aa-continue')?.addEventListener('click', onContinue);

  // Dev event log stays hidden in the customer/sales view; ?debug reveals it.
  if (new URLSearchParams(window.location.search).has('debug')) {
    $('#events-log')?.removeAttribute('hidden');
  }

  selectMethod('fonepay'); // Nepal → lead with Fonepay, the dominant local wallet
  handleEsewaReturn();      // if we're returning from eSewa, show the outcome
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', wire);
} else {
  wire();
}
