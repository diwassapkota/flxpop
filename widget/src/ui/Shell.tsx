import type { ReactNode } from 'react';
import type { Currency } from '../types';

/**
 * Widget chrome. In `compact` mode (the widget is embedded in a merchant
 * checkout that already shows the brand, amount and "secured by" marks) we drop
 * the header and secure footer so the widget is just the payment card — no
 * duplicated amount, no extra chrome, far less height.
 */
export function Shell({
  amount, currency, children, compact = false,
}: { amount: number; currency: Currency; children: ReactNode; compact?: boolean }) {
  if (compact) {
    return <div className="fp-shell fp-shell--compact">{children}</div>;
  }

  return (
    <div className="fp-shell">
      <header className="fp-header">
        <div className="fp-brand">
          <span className="fp-brand-mark" />
          <span>FLXPOP</span>
        </div>
        <div>
          <div className="fp-label" style={{ textAlign: 'right' }}>Amount due</div>
          <div className="fp-amount">
            <span className="fp-amount-ccy">{currency}</span>
            {formatMoney(amount, currency)}
          </div>
        </div>
      </header>

      {children}

      <footer className="fp-secure-row">
        <SecureIcon />
        <span>Secured by FlxPop · payment never touches the merchant</span>
      </footer>
    </div>
  );
}

function formatMoney(minor: number, currency: Currency): string {
  const scale = 100; // NPR/INR/MYR/THB are all 2-decimal — matches Currency.minorUnitScale on the engine.
  const major = minor / scale;
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(major) + (currency === 'NPR' ? '' : '');
}

function SecureIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="11" width="18" height="11" rx="2"/>
      <path d="M7 11V7a5 5 0 0110 0v4"/>
    </svg>
  );
}
