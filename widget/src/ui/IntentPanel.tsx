import type { TransactionResponse } from '../types';

export function IntentPanel({
  txn, onBack,
}: { txn: TransactionResponse; onBack: () => void }) {
  return (
    <div className="fp-card">
      <PhoneIcon />
      <div className="fp-instruction">
        Tap below to open your banking app and approve the payment with biometrics.
      </div>
      <a className="fp-button fp-button-primary" href={txn.app_intent_url!}>
        Open banking app
        <ExternalIcon />
      </a>
      <div className="fp-spinner" />
      <div className="fp-tiny">Waiting for approval · {txn.txn_id}</div>
      <button type="button" className="fp-button fp-button-ghost" onClick={onBack}>
        Choose a different method
      </button>
    </div>
  );
}

function PhoneIcon() {
  return (
    <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="var(--accent)"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="7" y="2" width="10" height="20" rx="2.4" />
      <line x1="11" y1="18.5" x2="13" y2="18.5" />
    </svg>
  );
}

function ExternalIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M14 4h6v6" />
      <path d="M10 14L20 4" />
      <path d="M20 14v6H4V4h6" />
    </svg>
  );
}
