import { useEffect, useMemo, useReducer, useRef, type ReactElement } from 'react';
import { ApiCallError, FlexPopClient, newIdempotencyKey } from './api';
import type {
  Gateway,
  MethodSummary,
  SessionResponse,
  TransactionResponse,
  TxnStatus,
} from './types';
import { MethodPicker } from './ui/MethodPicker';
import { BankPicker } from './ui/BankPicker';
import { EsewaPanel } from './ui/EsewaPanel';
import { QrPanel } from './ui/QrPanel';
import { StatusScreen } from './ui/StatusScreen';
import { Shell } from './ui/Shell';

/** Boot config the widget needs to do its job. Provided via URL params or postMessage. */
export interface BootConfig {
  engineBaseUrl: string;
  publishableKey: string;
  session: SessionResponse;
  /**
   * Optional preselected gateway. When set, the widget skips its own method
   * picker and initiates this gateway immediately — used when the merchant
   * checkout already shows the brands (e.g. separate Fonepay / eSewa tiles) and
   * deep-links straight into one.
   */
  gateway?: Gateway;
}

type State =
  | { phase: 'method-select' }
  | { phase: 'initiating'; gateway: Gateway }
  // `scanned` flips true on the socket's QR-verification message: the shopper
  // has scanned the QR / opened their bank app and now needs to approve. We
  // swap the QR/bank UI for a "confirm in your app" spinner so there's
  // immediate feedback instead of a static "waiting" screen.
  | { phase: 'awaiting-payment'; txn: TransactionResponse; scanned?: boolean }
  | { phase: 'settled';  txn: TransactionResponse }
  | { phase: 'failed';   txn?: TransactionResponse; message: string }
  | { phase: 'expired';  txn: TransactionResponse };

type Action =
  | { type: 'choose-method'; gateway: Gateway }
  | { type: 'initiated';     txn: TransactionResponse }
  | { type: 'qr-verified' }
  | { type: 'polled';        txn: TransactionResponse }
  | { type: 'errored';       message: string; txn?: TransactionResponse }
  | { type: 'cancel-back-to-methods' };

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'choose-method':
      return { phase: 'initiating', gateway: action.gateway };
    case 'initiated':
      return { phase: 'awaiting-payment', txn: action.txn };
    case 'qr-verified':
      // Only meaningful while still awaiting payment; ignore once terminal.
      return state.phase === 'awaiting-payment' ? { ...state, scanned: true } : state;
    case 'polled': {
      const status: TxnStatus = action.txn.status;
      if (status === 'SETTLED')               return { phase: 'settled',  txn: action.txn };
      if (status === 'FAILED')                return { phase: 'failed',   txn: action.txn, message: action.txn.failure_message ?? 'Payment failed' };
      if (status === 'EXPIRED')               return { phase: 'expired',  txn: action.txn };
      if (state.phase === 'awaiting-payment') return { ...state, txn: action.txn };
      return state;
    }
    case 'errored':
      return { phase: 'failed', txn: action.txn, message: action.message };
    case 'cancel-back-to-methods':
      return { phase: 'method-select' };
  }
}

export function App({ engineBaseUrl, publishableKey, session, gateway }: BootConfig) {
  const client = useMemo(
    () => new FlexPopClient(engineBaseUrl, publishableKey),
    [engineBaseUrl, publishableKey],
  );

  // A preselected gateway jumps straight to initiating it — no in-widget picker.
  const initialState: State = gateway
    ? { phase: 'initiating', gateway }
    : { phase: 'method-select' };
  const [state, dispatch] = useReducer(reducer, initialState);
  const idempotencyKeyRef = useRef<string | null>(null);

  // Auto-size the embedding iframe to the content height, so the host page
  // never shows an oversized empty frame or an inner scrollbar. We measure the
  // .fp-shell content element, not documentElement — html/body are height:100%
  // (for standalone use), which would otherwise just echo the iframe height.
  useEffect(() => {
    const el = document.querySelector('.fp-shell') as HTMLElement | null;
    if (!el) return;
    const post = () => {
      const h = Math.ceil(el.getBoundingClientRect().height);
      if (h > 0) {
        try { window.parent?.postMessage({ type: 'flexpop:resize', height: h }, '*'); } catch { /* */ }
      }
    };
    post();
    const ro = new ResizeObserver(post);
    ro.observe(el);
    return () => ro.disconnect();
  }, [state.phase]);

  // Initiate transaction when user picks a method.
  useEffect(() => {
    if (state.phase !== 'initiating') return;

    let cancelled = false;
    const key = idempotencyKeyRef.current ?? newIdempotencyKey();
    idempotencyKeyRef.current = key;

    (async () => {
      try {
        const txn = await client.createTransaction(session.session_id, state.gateway, key);
        if (cancelled) return;
        notifyParent({ type: 'flexpop:initiated', txn_id: txn.txn_id, status: txn.status });
        dispatch({ type: 'initiated', txn });
      } catch (err) {
        if (cancelled) return;
        dispatch({
          type: 'errored',
          message: err instanceof ApiCallError ? err.message : 'Unable to start payment',
        });
      }
    })();

    return () => { cancelled = true; };
  }, [state.phase === 'initiating' ? state.gateway : null, client, session.session_id]);

  // Poll for status while awaiting payment.
  useEffect(() => {
    if (state.phase !== 'awaiting-payment') return;
    const txnId = state.txn.txn_id;

    let stopped = false;
    let timer: number | undefined;

    const tick = async () => {
      if (stopped) return;
      try {
        const txn = await client.getTransaction(txnId);
        if (stopped) return;
        dispatch({ type: 'polled', txn });
        if (txn.status === 'SETTLED' || txn.status === 'FAILED' || txn.status === 'EXPIRED') {
          notifyParent({
            type: txn.status === 'SETTLED' ? 'flexpop:settled'
                 : txn.status === 'FAILED' ? 'flexpop:failed'
                 : 'flexpop:expired',
            txn_id: txn.txn_id,
            status: txn.status,
          });
          return;
        }
      } catch {
        // Soft-fail: poll again on next tick.
      }
      timer = window.setTimeout(tick, 2000);
    };
    timer = window.setTimeout(tick, 1500);

    return () => {
      stopped = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [state.phase === 'awaiting-payment' ? state.txn.txn_id : null, client]);

  // Real-time: when Fonepay hands us a payment socket, listen on it so we learn
  // the result the instant the shopper approves — not just on the next poll. The
  // socket is an accelerator, NOT the source of truth: on a payment message we
  // kick an immediate status re-check against the engine (which verifies via the
  // Fonepay status API). The 2s poll above keeps running as the fallback, so a
  // dropped/blocked socket never strands the payment.
  useEffect(() => {
    if (state.phase !== 'awaiting-payment') return;
    const url = state.txn.websocket_url;
    if (!url) return;
    const txnId = state.txn.txn_id;

    let stopped = false;
    let ws: WebSocket | null = null;
    let burst: number | undefined;

    // Briefly poll the engine fast (its own status poll lags up to a few
    // seconds) so we surface the verified terminal state right after the socket
    // says a payment happened.
    const verifyNow = () => {
      let n = 0;
      const tick = async () => {
        if (stopped) return;
        try {
          const txn = await client.getTransaction(txnId);
          if (stopped) return;
          dispatch({ type: 'polled', txn });
          if (txn.status === 'SETTLED' || txn.status === 'FAILED' || txn.status === 'EXPIRED') {
            notifyParent({
              type: txn.status === 'SETTLED' ? 'flexpop:settled'
                   : txn.status === 'FAILED' ? 'flexpop:failed'
                   : 'flexpop:expired',
              txn_id: txn.txn_id,
              status: txn.status,
            });
            return;
          }
        } catch { /* transient — keep trying within the burst window */ }
        if (++n < 15) burst = window.setTimeout(tick, 1000);
      };
      tick();
    };

    try {
      ws = new WebSocket(url);
      ws.onmessage = (e) => {
        const kind = parseFonepaySocketMessage(e.data);
        // QR scanned / bank app opened, payment not yet approved → show the
        // "confirm in your app" step. Payment approved/declined → verify.
        if (kind === 'verified') dispatch({ type: 'qr-verified' });
        else if (kind === 'payment') verifyNow();
      };
      // onerror / onclose: silently fall back to polling, which is still live.
    } catch { /* WebSocket unsupported or a bad URL — polling covers us. */ }

    return () => {
      stopped = true;
      if (burst) window.clearTimeout(burst);
      try { ws?.close(); } catch { /* already closing */ }
    };
  }, [state.phase === 'awaiting-payment' ? state.txn.txn_id : null, client]);

  // When the merchant deep-linked one gateway, there's no in-widget picker to go
  // back to (the merchant's own tiles switch methods) — so suppress the panels'
  // "choose a different method" back button to avoid a duplicate, dead control.
  const onBack = gateway ? undefined : () => dispatch({ type: 'cancel-back-to-methods' });

  return (
    <Shell amount={session.amount} currency={session.currency} compact={!!gateway}>
      {render(state, session.methods, (gw) => dispatch({ type: 'choose-method', gateway: gw }), onBack)}
    </Shell>
  );
}

function render(
  state: State,
  methods: MethodSummary[],
  onChoose: (gateway: Gateway) => void,
  onBack?: () => void,
): ReactElement {
  switch (state.phase) {
    case 'method-select':
      return <MethodPicker methods={methods} onChoose={onChoose} />;
    case 'initiating':
      return <StatusScreen kind="pending" title="Starting payment…" sub={`Routing to ${state.gateway}`} />;
    case 'awaiting-payment': {
      // The socket told us the QR was scanned / bank app opened. Drop the QR or
      // bank list and show a "confirm in your app" spinner until the payment
      // result arrives — immediate feedback the moment they scan.
      if (state.scanned) {
        return <StatusScreen kind="pending" title="Confirm in your app" sub="Approve the payment in your banking app to finish." />;
      }
      // Mobile + bank intents → BankPicker (per Fonepay's real flow: each bank
      // has its own intent scheme, user picks the one whose app they have).
      if (state.txn.device === 'MOBILE' && (state.txn.intents?.length ?? 0) > 0) {
        return <BankPicker txn={state.txn} onBack={onBack} />;
      }
      // Redirect gateways (eSewa) return an app_intent_url to a hosted payment
      // page that can't be iframed — open it in a new tab and keep polling.
      if (state.txn.app_intent_url) {
        return <EsewaPanel txn={state.txn} onBack={onBack} />;
      }
      // Anything that returned a QR payload renders one — covers desktop and
      // the graceful mobile fallback when the bank catalog wasn't reachable.
      if (state.txn.qr_payload) {
        return <QrPanel txn={state.txn} onBack={onBack} />;
      }
      return <StatusScreen kind="pending" title="Waiting for the gateway…" sub="No intent or QR yet" />;
    }
    case 'settled':
      return <StatusScreen kind="settled" title="Payment received" sub={`Reference ${state.txn.txn_id}`} />;
    case 'failed':
      return <StatusScreen kind="failed" title="Payment failed" sub={state.message} />;
    case 'expired':
      return <StatusScreen kind="failed" title="Payment expired" sub="The window for this payment closed. Start a new checkout." />;
  }
}

/**
 * Fonepay's payment socket sends `{ merchantId, deviceId, transactionStatus }`
 * where `transactionStatus` is itself a JSON *string* (double-encoded). It emits
 * a QR-verification message when the code is scanned (`QRVerified`) and a
 * payment message when the shopper approves/declines (`paymentSuccess`). We only
 * act on the payment message — and even then we re-verify against the engine
 * rather than trusting the socket's value. Returns 'payment', 'verified', or null.
 */
function parseFonepaySocketMessage(data: unknown): 'payment' | 'verified' | null {
  if (typeof data !== 'string') return null;
  try {
    const outer = JSON.parse(data) as Record<string, unknown>;
    const ts = outer.transactionStatus;
    const inner = (typeof ts === 'string' ? JSON.parse(ts) : ts ?? outer) as Record<string, unknown>;
    if (inner && typeof inner.paymentSuccess === 'boolean') return 'payment';
    // The doc renders the key with a stray leading space (" QRVerified") — accept both.
    if (inner && (inner.QRVerified || inner[' QRVerified'])) return 'verified';
    return null;
  } catch {
    return null;
  }
}

function notifyParent(msg: Record<string, unknown>) {
  try {
    window.parent?.postMessage(msg, '*');
  } catch {
    // window.parent can be unavailable in test contexts — non-fatal.
  }
}
