import type { BankIntent, TransactionResponse } from '../types';

export function BankPicker({
  txn, onBack,
}: { txn: TransactionResponse; onBack?: () => void }) {
  const intents = txn.intents ?? [];

  return (
    <div className="fp-card">
      <div className="fp-label">Pick your banking app</div>

      <div className="fp-methods" style={{ width: '100%' }}>
        {intents.map((intent, i) => (
          <a
            // package_name can repeat (the Fonepay app + member banks share one
            // scheme), so include the index to keep keys unique.
            key={`${intent.package_name}-${i}`}
            className="fp-method"
            href={intent.intent_url}
            // target="_top" launches the deep-link from the TOP window. Mobile
            // browsers block a cross-origin sub-frame (the widget iframe) from
            // navigating to a custom scheme like fonepay://, so without this the
            // tap silently does nothing. The bank app handles `qrPayload` and the
            // status poll brings the shopper back.
            target="_top"
            rel="noopener noreferrer"
          >
            <span className="fp-method-icon" style={{ background: '#0EA5A4' }}>
              {firstLetter(intent.bank_name)}
            </span>
            <span>
              <div className="fp-method-name">{intent.bank_name}</div>
              <div className="fp-method-sub">Tap to open</div>
            </span>
            <Chevron />
          </a>
        ))}
      </div>

      <div className="fp-waiting">
        <span className="fp-waiting-dot" />
        Waiting for approval…
      </div>
      {onBack && (
        <button type="button" className="fp-linkback" onClick={onBack}>
          Choose a different method
        </button>
      )}
    </div>
  );
}

function firstLetter(s: string): string {
  return (s.match(/[A-Za-z]/)?.[0] ?? 'B').toUpperCase();
}

function Chevron() {
  return (
    <svg className="fp-arrow" width="16" height="16" viewBox="0 0 24 24"
         fill="none" stroke="currentColor" strokeWidth="2.4"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <polyline points="9 6 15 12 9 18" />
    </svg>
  );
}

export type _BankIntentMarker = BankIntent;  // re-export for treeshakers
