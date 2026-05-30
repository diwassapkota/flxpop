import type { Gateway, MethodSummary } from '../types';

export function MethodPicker({
  methods, onChoose,
}: { methods: MethodSummary[]; onChoose: (gateway: Gateway) => void }) {
  return (
    <div className="fp-methods">
      <div className="fp-label">Pay with</div>
      {methods.map((m) => (
        <button
          key={m.gateway}
          type="button"
          className="fp-method"
          onClick={() => onChoose(m.gateway)}
        >
          <span className="fp-method-icon" style={{ background: gatewayColor(m.gateway) }}>
            {m.display_name.slice(0, 1)}
          </span>
          <span>
            <div className="fp-method-name">{m.display_name}</div>
            <div className="fp-method-sub">{gatewaySubtitle(m.gateway)}</div>
          </span>
          <Chevron />
        </button>
      ))}
    </div>
  );
}

function gatewayColor(gateway: Gateway): string {
  switch (gateway) {
    case 'FONEPAY':   return '#DC143C';
    case 'ESEWA':     return '#60BB46';
    case 'UPI':       return '#3F51B5';
    case 'PAYTM':     return '#00BAF2';
    case 'TNG':       return '#F58220';
    case 'FPX':       return '#0064B7';
    case 'PROMPTPAY': return '#0050A0';
    case 'TRUEMONEY': return '#F36F21';
  }
}

function gatewaySubtitle(gateway: Gateway): string {
  switch (gateway) {
    case 'FONEPAY':   return 'Open Fonepay app or scan QR';
    case 'ESEWA':     return 'eSewa wallet';
    case 'UPI':       return 'GPay / PhonePe / any UPI app';
    case 'PAYTM':     return 'Paytm wallet';
    case 'TNG':       return 'Touch ’n Go eWallet';
    case 'FPX':       return 'Online banking transfer';
    case 'PROMPTPAY': return 'PromptPay QR';
    case 'TRUEMONEY': return 'TrueMoney wallet';
  }
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
