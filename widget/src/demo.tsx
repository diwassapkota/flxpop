// Demo merchant page bootstrap. Hooks the textarea + boot button up to the
// real loader script (`/flexpop.js` served from public/) — exactly the same
// integration a real merchant would do.
//
// Convenience: if the textarea is empty when "Boot" is clicked, we auto-create
// a session against the local engine using the dev sk_* key. In production
// the merchant's BACKEND does this; sk_* never lives in the browser.
export {};

declare global {
  interface Window {
    FlexPop: {
      mount: (opts: Record<string, unknown>) => { destroy: () => void };
    };
  }
}

const ENGINE_BASE_URL  = (window as unknown as { __ENGINE?: string }).__ENGINE  ?? 'http://localhost:8080';
const WIDGET_ORIGIN    = window.location.origin;
const PUBLISHABLE_KEY  = 'pk_dev_local_FLEXPOPPUBLICKEY1234567890';
const DEMO_SECRET_KEY  = 'sk_dev_local_FLEXPOPDEVKEY1234567890'; // demo only — sk_* never goes to a real browser

let mounted: { destroy: () => void } | null = null;

function logEvent(message: string) {
  const log = document.getElementById('events-log');
  if (!log) return;
  const ts = new Date().toLocaleTimeString();
  log.textContent = `[${ts}] ${message}\n` + (log.textContent || '');
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
      'Sec-CH-UA-Mobile': '?0',  // force DESKTOP routing for the demo
    },
    body: JSON.stringify({
      amount: 3050000,
      currency: 'NPR',
      country: 'NP',
      reference: 'ACME-AIR-DEMO',
    }),
  });
  if (!res.ok) {
    throw new Error(`Engine returned ${res.status} — is mvn spring-boot:run up at ${ENGINE_BASE_URL}?`);
  }
  return res.json();
}

async function boot() {
  const ta = document.getElementById('session-json') as HTMLTextAreaElement | null;
  if (!ta) return;

  let session: unknown;
  const raw = ta.value.trim();
  if (raw.length === 0) {
    logEvent('→ textarea empty — creating a fresh session via /v1/sessions…');
    try {
      session = await createDemoSession();
      ta.value = JSON.stringify(session, null, 2);
      logEvent(`✓ session created`);
    } catch (err) {
      logEvent(`✗ ${(err as Error).message}`);
      return;
    }
  } else {
    try {
      session = JSON.parse(raw);
    } catch (err) {
      logEvent(`✗ Invalid JSON: ${(err as Error).message}`);
      return;
    }
  }

  await ensureLoader();
  if (mounted) {
    mounted.destroy();
    mounted = null;
  }
  mounted = window.FlexPop.mount({
    container: '#checkout',
    widgetOrigin: WIDGET_ORIGIN,
    engineBaseUrl: ENGINE_BASE_URL,
    publishableKey: PUBLISHABLE_KEY,
    session,
    onInitiated: (e: Record<string, unknown>) => logEvent(`→ initiated  txn_id=${e.txn_id} status=${e.status}`),
    onSettled:   (e: Record<string, unknown>) => logEvent(`✓ SETTLED    txn_id=${e.txn_id}`),
    onFailed:    (e: Record<string, unknown>) => logEvent(`✗ FAILED     txn_id=${e.txn_id}`),
    onExpired:   (e: Record<string, unknown>) => logEvent(`! EXPIRED    txn_id=${e.txn_id}`),
  });
  logEvent('Widget mounted');
}

document.getElementById('boot-btn')?.addEventListener('click', () => { boot(); });
