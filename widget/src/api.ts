import type {
  ApiError,
  Gateway,
  TransactionResponse,
} from './types';

/** Random idempotency key for a single widget instance's transaction-create call. */
export function newIdempotencyKey(): string {
  // crypto.randomUUID is available in all modern browsers.
  return 'widget-' + crypto.randomUUID();
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
