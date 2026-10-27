import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { BankStatus, ExceptionDetail, Row } from '../api/types';
import { Card, ClassBadge, ErrorBanner, StatusBadge } from '../components/common';
import { formatPaise, humanize } from '../format';

export const RESOLUTIONS = ['BANK_CONFIRMED', 'RECOVERED_FROM_GATEWAY', 'LEDGER_CORRECTED', 'DUPLICATE_REFUNDED',
  'WRITTEN_OFF', 'FALSE_POSITIVE', 'OTHER'];

function Value({ k, v }: { k: string; v: Row[string] }) {
  if (v === null || v === undefined || v === '') return <span className="muted">—</span>;
  if (typeof v === 'number' && k.endsWith('minor')) return <>{formatPaise(v)}</>;
  return <>{String(v)}</>;
}

function SourcePanel({ title, rows, empty }: { title: string; rows: Row[]; empty: string }) {
  return (
    <Card title={title} className={rows.length === 0 ? 'side missing' : 'side'}>
      {rows.length === 0 && <p className="missing-text">{empty}</p>}
      {rows.map((r, i) => (
        <dl key={i} className="kv">
          {rows.length > 1 && <div className="kv-title">Record {i + 1} of {rows.length}</div>}
          {Object.entries(r).map(([k, v]) => (
            <div key={k}><dt>{humanize(k)}</dt><dd className="mono"><Value k={k} v={v} /></dd></div>
          ))}
        </dl>
      ))}
    </Card>
  );
}

export function ExceptionDetailPage() {
  const { id } = useParams();
  const exceptionId = Number(id);
  const { api, can, session } = useAuth();
  const [d, setD] = useState<ExceptionDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [comment, setComment] = useState('');
  const [assignee, setAssignee] = useState('');
  const [reason, setReason] = useState(RESOLUTIONS[0]);
  const [note, setNote] = useState('');
  const [bank, setBank] = useState<BankStatus | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setD(await api.exception(exceptionId));
    } catch (e) {
      setError(e);
    }
  }, [api, exceptionId]);

  useEffect(() => { void load(); }, [load]);

  async function act(fn: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  if (!d) return <div className="page"><ErrorBanner error={error} />{!error && <p>Loading…</p>}</div>;
  const e = d.exception;
  const open = e.status !== 'RESOLVED' && e.status !== 'AUTO_RESOLVED';
  const canWork = can('OPS_ANALYST', 'OPS_SUPERVISOR', 'ADMIN');
  const pendingAdj = d.adjustment?.status === 'PENDING_APPROVAL';

  return (
    <div className="page">
      <p><Link to="/exceptions">← Exceptions</Link></p>
      <div className="page-head">
        <h1>Exception #{e.id} <span className="mono small">{e.entityId}</span></h1>
        <div className="actions"><ClassBadge value={e.exceptionClass} /><StatusBadge status={e.status} /></div>
      </div>
      <div className="summary-line">
        <span><strong>{humanize(e.reason)}</strong></span>
        <span>At risk <strong data-testid="at-risk">{formatPaise(e.amountAtRiskMinor, e.currency)}</strong></span>
        <span>Business date {e.businessDate}</span>
        <span>Merchant {e.merchantId}</span>
        <span>Assignee {e.assignee ?? '—'}</span>
        {e.resolutionReason && <span>Resolution {humanize(e.resolutionReason)}</span>}
      </div>
      {d.reconResult && <p className="muted small">Findings: {String(d.reconResult.findings).split(';').map(humanize).join(' · ')} · confidence {String(d.reconResult.confidence)}</p>}
      <ErrorBanner error={error} />
      <div className="sides">
        <SourcePanel title="Merchant ledger" rows={d.ledger} empty="No ledger entry: the merchant never recorded this payment." />
        <SourcePanel title="Gateway settlement" rows={d.gateway} empty="Not in any gateway settlement file." />
        <SourcePanel title="Bank statement" rows={d.bank} empty="No bank credit received." />
      </div>
      <div className="grid2">
        <Card title="Actions">
          {d.adjustment && (
            <div className="notice">
              Adjustment journal #{d.adjustment.id}: <strong>{formatPaise(d.adjustment.amountMinor, d.adjustment.currency)}</strong> · {humanize(d.adjustment.status)}
              {d.adjustment.approvedBy && <> by {d.adjustment.approvedBy}</>}
              {pendingAdj && can('OPS_SUPERVISOR', 'ADMIN') && (
                <button className="primary" disabled={busy} onClick={() => act(() => api.approveAdjustment(d.adjustment!.id))}>Approve adjustment</button>
              )}
              {pendingAdj && !can('OPS_SUPERVISOR', 'ADMIN') && <span className="muted"> · needs an OPS_SUPERVISOR</span>}
            </div>
          )}
          {open && canWork && (
            <>
              <div className="row">
                <input aria-label="assignee" placeholder="assignee" value={assignee} onChange={(ev) => setAssignee(ev.target.value)} />
                <button disabled={busy || !assignee} onClick={() => act(() => api.assign(e.id, assignee))}>Assign</button>
                <button disabled={busy} onClick={() => act(() => api.assign(e.id, session!.username))}>Assign to me</button>
              </div>
              <div className="row">
                <select aria-label="resolution reason" value={reason} onChange={(ev) => setReason(ev.target.value)}>
                  {RESOLUTIONS.map((r) => <option key={r} value={r}>{humanize(r)}</option>)}
                </select>
                <input aria-label="resolution note" placeholder="note" value={note} onChange={(ev) => setNote(ev.target.value)} />
                <button className="primary" disabled={busy} onClick={() => act(() => api.resolve(e.id, reason, note))}>Resolve</button>
              </div>
            </>
          )}
          <div className="row">
            <button disabled={busy || !d.reconResult?.utr} onClick={() => act(async () => setBank(await api.bankStatus(e.id)))}>Ask bank (SOAP)</button>
            {bank && (
              <span data-testid="bank-status">Bank says <strong>{humanize(bank.status)}</strong>{bank.creditCount > 0 && <> · {bank.creditCount} credit(s) · {formatPaise(bank.amountMinor, bank.currency)} on {bank.valueDate}</>}</span>
            )}
          </div>
        </Card>
        <Card title="Comments">
          <ul className="comments">
            {d.comments.map((c) => <li key={c.id}><strong>{c.author}</strong> <span className="muted small">{new Date(c.createdAt).toLocaleString()}</span><p>{c.body}</p></li>)}
            {d.comments.length === 0 && <li className="muted">No comments yet.</li>}
          </ul>
          {canWork && (
            <div className="row">
              <textarea aria-label="comment" value={comment} onChange={(ev) => setComment(ev.target.value)} rows={2} />
              <button disabled={busy || !comment.trim()} onClick={() => act(async () => { await api.comment(e.id, comment); setComment(''); })}>Add comment</button>
            </div>
          )}
        </Card>
      </div>
      <Card title="History (audit log)">
        <ol className="history">
          {d.history.map((h) => <li key={h.id}><span className="muted small">{new Date(h.occurredAt).toLocaleString()}</span> <strong>{humanize(h.action)}</strong> by {h.actor}{h.details && <> · {h.details}</>}</li>)}
        </ol>
      </Card>
    </div>
  );
}
