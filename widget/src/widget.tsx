import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { App, type BootConfig } from './App';
import type { SessionResponse } from './types';
import './styles.css';

/**
 * Widget entry point. Boots from URL params or postMessage from the parent.
 *
 * URL-params form (easiest for demos and curl-driven smoke tests):
 *   /?engine=http://localhost:8080
 *    &pk=pk_dev_local_FLEXPOPPUBLICKEY1234567890
 *    &session=<base64-url-encoded SessionResponse JSON>
 *
 * postMessage form (production):
 *   parent.postMessage({ type: 'flexpop:boot',
 *                        engineBaseUrl: '...', publishableKey: '...', session: {...} },
 *                      'https://widget.flexpop.io');
 */
function Bootstrap() {
  const [config, setConfig] = useState<BootConfig | null>(() => readUrlParams());

  useEffect(() => {
    if (config) return;
    const onMessage = (e: MessageEvent) => {
      const data = e.data;
      if (!data || data.type !== 'flexpop:boot') return;
      if (!isValidBoot(data)) return;
      setConfig({
        engineBaseUrl: data.engineBaseUrl,
        publishableKey: data.publishableKey,
        session: data.session as SessionResponse,
      });
    };
    window.addEventListener('message', onMessage);
    // Tell the parent we're ready to receive the boot config.
    try { window.parent?.postMessage({ type: 'flexpop:ready' }, '*'); } catch { /* */ }
    return () => window.removeEventListener('message', onMessage);
  }, [config]);

  if (!config) {
    return (
      <div className="fp-shell">
        <div className="fp-spinner" />
        <div className="fp-tiny">Waiting for boot…</div>
      </div>
    );
  }

  return <App {...config} />;
}

function readUrlParams(): BootConfig | null {
  const params = new URLSearchParams(window.location.search);
  const engine = params.get('engine');
  const pk = params.get('pk');
  const sessionParam = params.get('session');
  if (!engine || !pk || !sessionParam) return null;
  try {
    const decoded = decodeURIComponent(escape(atob(sessionParam.replace(/-/g, '+').replace(/_/g, '/'))));
    const session = JSON.parse(decoded) as SessionResponse;
    return { engineBaseUrl: engine, publishableKey: pk, session };
  } catch {
    return null;
  }
}

function isValidBoot(data: unknown): data is BootConfig & { type: 'flexpop:boot' } {
  if (typeof data !== 'object' || data === null) return false;
  const d = data as Record<string, unknown>;
  return typeof d.engineBaseUrl === 'string'
      && typeof d.publishableKey === 'string'
      && typeof d.session === 'object' && d.session !== null;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Bootstrap />
  </StrictMode>,
);
