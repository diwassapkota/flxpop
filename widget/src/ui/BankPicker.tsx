import type { BankIntent, TransactionResponse } from '../types';

export function BankPicker({
  txn, onBack,
}: { txn: TransactionResponse; onBack: () => void }) {
  const intents = txn.intents ?? [];

  return (
    <div className="fp-card">
      <div className="fp-label">Pick your banking app</div>

      <div className="fp-methods" style={{ width: '100%' }}>
        {intents.map((intent) => (
          <a
            key={intent.package_name}
            className="fp-method"
            href={intent.intent_url}
            // Native link so iOS/Android intent resolution kicks in; no
            // preventDefault, no router. The bank app handles `qrPayload`
            // and brings the user back to FlexPop via the status poll.
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

      <div className="fp-spinner" />
      <div className="fp-tiny">Waiting for approval · {txn.txn_id}</div>
      <button type="button" className="fp-button fp-button-ghost" onClick={onBack}>
        Choose a different method
      </button>
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
