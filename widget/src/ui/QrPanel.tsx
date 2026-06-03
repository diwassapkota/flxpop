import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';
import type { TransactionResponse } from '../types';

export function QrPanel({
  txn, onBack,
}: { txn: TransactionResponse; onBack?: () => void }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    if (!canvasRef.current || !txn.qr_payload) return;
    QRCode.toCanvas(canvasRef.current, txn.qr_payload, {
      width: 200,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#0F1B2D', light: '#FFFFFF' },
    }).catch(() => {
      // Rendering failed (extremely rare) — leave the canvas blank.
    });
  }, [txn.qr_payload]);

  return (
    <div className="fp-card">
      <div className="fp-label">Scan with your banking app</div>
      <div className="fp-qr">
        <canvas ref={canvasRef} />
      </div>
      <div className="fp-instruction">
        Open <strong>{txn.gateway}</strong> on your phone and scan to approve the payment.
      </div>
      <div className="fp-waiting">
        <span className="fp-waiting-dot" />
        Waiting for payment…
      </div>
      {onBack && (
        <button type="button" className="fp-linkback" onClick={onBack}>
          Choose a different method
        </button>
      )}
    </div>
  );
}
