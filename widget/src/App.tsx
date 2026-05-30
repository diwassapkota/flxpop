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
import { IntentPanel } from './ui/IntentPanel';
import { QrPanel } from './ui/QrPanel';
import { StatusScreen } from './ui/StatusScreen';
import { Shell } from './ui/Shell';

/** Boot config the widget needs to do its job. Provided via URL params or postMessage. */
export interface BootConfig {
  engineBaseUrl: string;
  publishableKey: string;
  session: SessionResponse;
}

type State =
  | { phase: 'method-select' }
  | { phase: 'initiating'; gateway: Gateway }
  | { phase: 'awaiting-payment'; txn: TransactionResponse }
  | { phase: 'settled';  txn: TransactionResponse }
  | { phase: 'failed';   txn?: TransactionResponse; message: string }
  | { phase: 'expired';  txn: TransactionResponse };

type Action =
  | { type: 'choose-method'; gateway: Gateway }
  | { type: 'initiated';     txn: TransactionResponse }
  | { type: 'polled';        txn: TransactionResponse }
  | { type: 'errored';       message: string; txn?: TransactionResponse }
  | { type: 'cancel-back-to-methods' };

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'choose-method':
      return { phase: 'initiating', gateway: action.gateway };
    case 'initiated':
      return { phase: 'awaiting-payment', txn: action.txn };
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

export function App({ engineBaseUrl, publishableKey, session }: BootConfig) {
  const client = useMemo(
    () => new FlexPopClient(engineBaseUrl, publishableKey),
    [engineBaseUrl, publishableKey],
  );

  const [state, dispatch] = useReducer(reducer, { phase: 'method-select' } as State);
  const idempotencyKeyRef = useRef<string | null>(null);

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

  return (
    <Shell amount={session.amount} currency={session.currency}>
      {render(state, session.methods, (gw) => dispatch({ type: 'choose-method', gateway: gw }),
        () => dispatch({ type: 'cancel-back-to-methods' }))}
    </Shell>
  );
}

function render(
  state: State,
  methods: MethodSummary[],
  onChoose: (gateway: Gateway) => void,
  onBack: () => void,
): ReactElement {
  switch (state.phase) {
    case 'method-select':
      return <MethodPicker methods={methods} onChoose={onChoose} />;
    case 'initiating':
      return <StatusScreen kind="pending" title="Starting payment…" sub={`Routing to ${state.gateway}`} />;
    case 'awaiting-payment': {
      if (state.txn.app_intent_url) {
        return <IntentPanel txn={state.txn} onBack={onBack} />;
      }
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

function notifyParent(msg: Record<string, unknown>) {
  try {
    window.parent?.postMessage(msg, '*');
  } catch {
    // window.parent can be unavailable in test contexts — non-fatal.
  }
}
