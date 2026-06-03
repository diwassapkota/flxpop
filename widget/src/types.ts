// Mirrors com.flexpop.engine.api.dto on the engine side. Keep these in sync.

export type Currency = 'NPR' | 'INR' | 'MYR' | 'THB';
export type Country  = 'NP' | 'IN' | 'MY' | 'TH';
export type Device   = 'MOBILE' | 'DESKTOP';
export type Gateway  = 'FONEPAY' | 'ESEWA' | 'UPI' | 'PAYTM' | 'TNG' | 'FPX' | 'PROMPTPAY' | 'TRUEMONEY';
export type TxnStatus = 'CREATED' | 'ROUTED' | 'PENDING' | 'SETTLED' | 'FAILED' | 'EXPIRED' | 'REFUNDED';

export interface MethodSummary {
  gateway: Gateway;
  display_name: string;
}

export interface SessionResponse {
  session_id: string;
  amount: number;
  currency: Currency;
  country: Country;
  device: Device;
  methods: MethodSummary[];
  expires_at: string;
}

export interface TransactionResponse {
  txn_id: string;
  amount: number;
  refunded_amount: number;
  currency: Currency;
  country: Country;
  device: Device;
  gateway: Gateway;
  status: TxnStatus;
  gateway_ref?: string | null;
  app_intent_url?: string | null;
  qr_payload?: string | null;
  // Real-time Fonepay payment socket (mobile, while awaiting payment). The
  // widget opens it to learn the result instantly instead of waiting on a poll.
  websocket_url?: string | null;
  expires_at?: string | null;
  settled_at?: string | null;
  failure_code?: string | null;
  failure_message?: string | null;
  intents?: BankIntent[] | null;
  events: TxnEvent[];
}

export interface BankIntent {
  bank_name: string;
  package_name: string;
  intent_url: string;
}

export interface TxnEvent {
  event_id: string;
  type: string;
  source: 'ENGINE' | 'GATEWAY' | 'MERCHANT' | 'SYSTEM';
  payload?: Record<string, unknown>;
  occurred_at: string;
}

export interface ApiError {
  error: {
    type: string;
    message: string;
    request_id: string;
  };
}
