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
  | { type: 'socket-result'; success: boolean }
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
    case 'socket-result':
      // Terminal result delivered by Fonepay's payment socket — drive the
      // result screen straight off it (the engine's status poll can't confirm).
      if (state.phase !== 'awaiting-payment') return state;
      return action.success
        ? { phase: 'settled', txn: state.txn }
        : { phase: 'failed', txn: state.txn, message: state.txn.failure_message ?? 'Payment failed' };
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

  // Poll the engine for status while awaiting payment. This is the durable
  // backstop: the engine settles Fonepay txns from its OWN server-side socket
  // (FonepaySocketManager), so polling picks up the result even if the browser
  // socket missed it — and it's the path for socket-less gateways (eSewa) too.
  // The browser socket below still runs for instant feedback on top of this.
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

  // Real-time result straight from Fonepay's payment socket.
  //
  // The socket is the authoritative channel the doc (§5.1, §9.5) gives the web
  // client: a QR-verification frame on scan, then a payment frame carrying
  // `paymentSuccess` on approve/decline. We drive the result screen directly
  // off that frame. We deliberately do NOT round-trip through the engine to
  // confirm: Fonepay's status API (thirdPartyDynamicQrGetStatus) returns 409
  // for this merchant, so the engine's poll never settles — waiting on it is
  // exactly why the redirect was stuck. Server-side fulfilment still relies on
  // the engine's webhook/reconciliation, not this presentational signal. The 2s
  // poll above stays as a fallback for transactions with no socket URL.
  useEffect(() => {
    if (state.phase !== 'awaiting-payment') return;
    const url = state.txn.websocket_url;
    if (!url) return;
    const txnId = state.txn.txn_id;

    let stopped = false;
    let ws: WebSocket | null = null;

    try {
      ws = new WebSocket(url);
      ws.onmessage = (e) => {
        if (stopped) return;
        const r = parseFonepaySocketMessage(e.data);
        if (r === 'verified') {
          dispatch({ type: 'qr-verified' });                 // scanned → "confirm in your app"
        } else if (r === 'success') {
          dispatch({ type: 'socket-result', success: true });
          notifyParent({ type: 'flexpop:settled', txn_id: txnId, status: 'SETTLED' });
        } else if (r === 'failed') {
          dispatch({ type: 'socket-result', success: false });
          notifyParent({ type: 'flexpop:failed', txn_id: txnId, status: 'FAILED' });
        }
      };
      // onerror / onclose: silently fall back to polling.
    } catch { /* WebSocket unsupported or a bad URL — polling covers us. */ }

    return () => {
      stopped = true;
      try { ws?.close(); } catch { /* already closing */ }
    };
  }, [state.phase === 'awaiting-payment' ? state.txn.txn_id : null]);

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
 * where `transactionStatus` is itself a JSON *string* (double-encoded):
 *
 *   scan:    {"success":true,"message":"VERIFIED","qrVerified":true}
 *   paid:    {...,"message":"RES000","success":true,"paymentSuccess":true}
 *
 * Field names verified against the live production socket — they differ from the
 * doc (§9.5.1), which shows " QRVerified" (caps + stray space); production sends
 * `qrVerified`. We accept all variants. Returns:
 *   'verified' → QR scanned, awaiting approval
 *   'success' / 'failed' → terminal payment result
 *   null → anything else (keepalive, unknown frame)
 */
function parseFonepaySocketMessage(data: unknown): 'verified' | 'success' | 'failed' | null {
  if (typeof data !== 'string') return null;
  try {
    const outer = JSON.parse(data) as Record<string, unknown>;
    const ts = outer.transactionStatus;
    const inner = (typeof ts === 'string' ? JSON.parse(ts) : ts ?? outer) as Record<string, unknown>;
    if (!inner) return null;
    // Payment frame: carries paymentSuccess. (true → settled, false → failed.)
    if (typeof inner.paymentSuccess === 'boolean') return inner.paymentSuccess ? 'success' : 'failed';
    // Verification frame: real key is `qrVerified`; accept doc variants too.
    if (inner.qrVerified || inner.QRVerified || inner[' QRVerified']) return 'verified';
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
