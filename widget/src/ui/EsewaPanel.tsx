import type { TransactionResponse } from '../types';

/**
 * Redirect-gateway panel (eSewa). eSewa's hosted login/OTP page sends
 * X-Frame-Options: DENY, so it can't live in the widget iframe. The smartest
 * UX for a wallet credential page is a full-page redirect (mobile-safe, no
 * popup blockers, real esewa.com.np in the address bar): we navigate the whole
 * tab to the engine-hosted checkout, which auto-submits to eSewa. After paying,
 * eSewa → our signed callback → 302 back to the merchant checkout with the
 * result in the query string.
 */
export function EsewaPanel({
  txn, onBack,
}: { txn: TransactionResponse; onBack?: () => void }) {
  const url = txn.app_intent_url ?? undefined;

  const goToEsewa = () => {
    if (!url) return;
    // The widget is framed by the merchant page; redirect the TOP window so we
    // leave the frame entirely (eSewa refuses to render inside any iframe).
    try {
      window.top!.location.assign(url);
    } catch {
      // Cross-origin top access denied (sandboxed embed) — fall back to a tab.
      window.open(url, '_blank', 'noopener,noreferrer');
    }
  };

  return (
    <div className="fp-card">
      <div className="fp-label">Pay with eSewa</div>

      <EsewaMark />

      <div className="fp-instruction">
        You’ll be taken to eSewa’s secure page to log in and approve the payment,
        then brought right back here to your order.
      </div>

      <button
        type="button"
        className="fp-button fp-button-primary"
        style={{ background: '#5DBB46', width: '100%' }}
        onClick={goToEsewa}
      >
        Continue to eSewa
      </button>

      <div className="fp-secure-row" style={{ justifyContent: 'center' }}>
        <LockIcon />
        <span>You’ll see <strong>esewa.com.np</strong> in your address bar</span>
      </div>

      {onBack && (
        <button type="button" className="fp-linkback" onClick={onBack}>
          Choose a different method
        </button>
      )}
    </div>
  );
}

function EsewaMark() {
  return (
    <div
      style={{
        height: 40, padding: '0 16px', borderRadius: 10,
        background: '#5DBB46', color: '#fff',
        display: 'inline-flex', alignItems: 'center', gap: 8,
        fontWeight: 800, fontSize: 17, letterSpacing: '-0.02em',
      }}
    >
      <span
        style={{
          width: 22, height: 22, borderRadius: 6,
          background: 'rgba(255,255,255,.22)',
          display: 'grid', placeItems: 'center', fontSize: 14,
        }}
      >
        e
      </span>
      eSewa
    </div>
  );
}

function LockIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="11" width="18" height="11" rx="2" />
      <path d="M7 11V7a5 5 0 0110 0v4" />
    </svg>
  );
}
