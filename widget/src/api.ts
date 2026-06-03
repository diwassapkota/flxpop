import type {
  ApiError,
  Gateway,
  TransactionResponse,
} from './types';

/** Random idempotency key for a single widget instance's transaction-create call. */
export function newIdempotencyKey(): string {
  return 'widget-' + uuidv4();
}

/**
 * A v4 UUID that also works on insecure origins.
 *
 * `crypto.randomUUID()` is only defined in a *secure context* (HTTPS or
 * localhost). A phone hitting the dev server over plain HTTP on a LAN IP
 * (e.g. http://10.0.0.5:5173) is NOT a secure context, so randomUUID is
 * undefined there — calling it crashed the widget mid-checkout and left the
 * frame blank. `crypto.getRandomValues()` IS available on insecure origins, so
 * we fall back to it and assemble the UUID by hand.
 */
function uuidv4(): string {
  const c = globalThis.crypto;
  if (typeof c?.randomUUID === 'function') return c.randomUUID();
  const b = new Uint8Array(16);
  c.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40; // version 4
  b[8] = (b[8] & 0x3f) | 0x80; // RFC 4122 variant
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0'));
  return `${h[0]}${h[1]}${h[2]}${h[3]}-${h[4]}${h[5]}-${h[6]}${h[7]}-${h[8]}${h[9]}-${h[10]}${h[11]}${h[12]}${h[13]}${h[14]}${h[15]}`;
}

export class FlexPopClient {
  constructor(
    private readonly baseUrl: string,
    private readonly publishableKey: string,
  ) {}

  // Note: the widget is bootstrapped with the SessionResponse from the parent
  // (via postMessage or URL params), so there's no widget-side GET /v1/sessions
  // call. If the engine ever adds GET /v1/sessions/{id}, wire it here.

  async createTransaction(
    sessionId: string,
    gateway: Gateway,
    idempotencyKey: string,
  ): Promise<TransactionResponse> {
    const res = await fetch(`${this.baseUrl}/v1/transactions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${this.publishableKey}`,
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ session_id: sessionId, gateway }),
    });
    return parse<TransactionResponse>(res);
  }

  async getTransaction(txnId: string): Promise<TransactionResponse> {
    const res = await fetch(`${this.baseUrl}/v1/transactions/${txnId}`, {
      headers: { Authorization: `Bearer ${this.publishableKey}` },
    });
    return parse<TransactionResponse>(res);
  }
}

async function parse<T>(res: Response): Promise<T> {
  const text = await res.text();
  if (!res.ok) {
    let body: ApiError | undefined;
    try { body = JSON.parse(text) as ApiError; } catch { /* non-JSON */ }
    const message = body?.error?.message ?? `${res.status} ${res.statusText}`;
    throw new ApiCallError(message, res.status, body?.error?.type ?? 'http_error');
  }
  return JSON.parse(text) as T;
}

export class ApiCallError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly type: string,
  ) {
    super(message);
  }
}
