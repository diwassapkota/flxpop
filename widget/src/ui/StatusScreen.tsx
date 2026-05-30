export function StatusScreen({
  kind, title, sub,
}: { kind: 'pending' | 'settled' | 'failed'; title: string; sub?: string }) {
  return (
    <div className="fp-status">
      {kind === 'pending'
        ? <div className="fp-spinner" />
        : <div className={`fp-status-icon ${kind}`}>{kind === 'settled' ? '✓' : '!'}</div>
      }
      <div className="fp-status-title">{title}</div>
      {sub ? <div className="fp-status-sub">{sub}</div> : null}
    </div>
  );
}
