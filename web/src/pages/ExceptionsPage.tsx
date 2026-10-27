import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { ExceptionFilter, ExceptionView, Page } from '../api/types';
import { ClassBadge, ErrorBanner, Pagination, StatusBadge } from '../components/common';
import { formatPaise, humanize, parseRupeesToPaise } from '../format';

export const CLASSES = ['MISSING_IN_LEDGER', 'MISSING_IN_GATEWAY', 'MISSING_IN_BANK', 'DUPLICATE', 'AMOUNT_MISMATCH',
  'LATE_SETTLEMENT', 'ORPHAN_BANK_CREDIT'];
export const STATUSES = ['OPEN', 'IN_REVIEW', 'PENDING_EXPECTED', 'RESOLVED', 'AUTO_RESOLVED'];
const FIELDS = ['exceptionClass', 'status', 'merchantId', 'from', 'to', 'minAmount', 'entityId', 'sort'] as const;

export function ExceptionsPage() {
  const { api } = useAuth();
  const [params, setParams] = useSearchParams();
  const [data, setData] = useState<Page<ExceptionView> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [draft, setDraft] = useState<Record<string, string>>(() =>
    Object.fromEntries(FIELDS.map((f) => [f, params.get(f) ?? (f === 'status' ? 'OPEN,IN_REVIEW' : '')])));
  const page = Number(params.get('page') ?? 0);

  useEffect(() => {
    const filter: ExceptionFilter = {
      exceptionClass: params.get('exceptionClass') ?? undefined,
      status: params.get('status') ?? 'OPEN,IN_REVIEW',
      merchantId: params.get('merchantId') ?? undefined,
      from: params.get('from') ?? undefined,
      to: params.get('to') ?? undefined,
      entityId: params.get('entityId') ?? undefined,
      sort: params.get('sort') ?? undefined,
      minAmountMinor: params.get('minAmount') ? parseRupeesToPaise(params.get('minAmount')!) ?? undefined : undefined,
      page,
      size: 25,
    };
    let cancelled = false;
    setError(null);
    api.exceptions(filter).then((d) => { if (!cancelled) setData(d); }).catch((e) => { if (!cancelled) setError(e); });
    return () => { cancelled = true; };
  }, [api, params, page]);

  function apply(e: FormEvent) {
    e.preventDefault();
    if (draft.minAmount && parseRupeesToPaise(draft.minAmount) === null) {
      setError(new Error('Minimum amount must be a rupee amount like 1500 or 1500.50'));
      return;
    }
    const next = new URLSearchParams();
    Object.entries(draft).forEach(([k, v]) => { if (v) next.set(k, v); });
    setParams(next);
  }

  function goTo(p: number) {
    const next = new URLSearchParams(params);
    next.set('page', String(p));
    setParams(next);
  }

  const set = (k: string) => (e: { target: { value: string } }) => setDraft({ ...draft, [k]: e.target.value });

  return (
    <div className="page">
      <div className="page-head"><h1>Exceptions</h1><span className="muted">{data ? `${data.totalElements.toLocaleString('en-IN')} matching` : ''}</span></div>
      <form className="filters card" onSubmit={apply} aria-label="filters">
        <label>Type<select value={draft.exceptionClass} onChange={set('exceptionClass')}>
          <option value="">All</option>{CLASSES.map((c) => <option key={c} value={c}>{humanize(c)}</option>)}</select></label>
        <label>Status<select value={draft.status} onChange={set('status')}>
          <option value="OPEN,IN_REVIEW">Unresolved</option><option value="">All</option>
          {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}</select></label>
        <label>Merchant<input value={draft.merchantId} onChange={set('merchantId')} placeholder="M001" /></label>
        <label>From<input type="date" value={draft.from} onChange={set('from')} /></label>
        <label>To<input type="date" value={draft.to} onChange={set('to')} /></label>
        <label>Min at risk (₹)<input value={draft.minAmount} onChange={set('minAmount')} inputMode="decimal" /></label>
        <label>Payment / ref<input value={draft.entityId} onChange={set('entityId')} placeholder="PAY2026…" /></label>
        <label>Sort<select value={draft.sort} onChange={set('sort')}>
          <option value="">Largest at risk</option><option value="businessDate,asc">Oldest first</option>
          <option value="businessDate,desc">Newest first</option><option value="updatedAt,desc">Recently updated</option></select></label>
        <button type="submit" className="primary">Apply</button>
      </form>
      <ErrorBanner error={error} />
      <div className="card table-wrap">
        <table className="data" aria-label="exceptions">
          <thead><tr><th>#</th><th>Business date</th><th>Merchant</th><th>Entity</th><th>Type</th><th>Reason</th><th className="num">At risk</th><th>Status</th><th>Assignee</th></tr></thead>
          <tbody>
            {data?.content.map((e) => (
              <tr key={e.id}>
                <td><Link to={`/exceptions/${e.id}`}>{e.id}</Link></td>
                <td>{e.businessDate}</td>
                <td>{e.merchantId}</td>
                <td className="mono"><Link to={`/exceptions/${e.id}`}>{e.entityId}</Link></td>
                <td><ClassBadge value={e.exceptionClass} /></td>
                <td>{humanize(e.reason)}</td>
                <td className="num">{formatPaise(e.amountAtRiskMinor, e.currency)}</td>
                <td><StatusBadge status={e.status} /></td>
                <td>{e.assignee ?? '—'}</td>
              </tr>
            ))}
            {data && data.content.length === 0 && <tr><td colSpan={9} className="muted">No exceptions match these filters.</td></tr>}
          </tbody>
        </table>
      </div>
      {data && <Pagination page={data.page} totalPages={data.totalPages} onPage={goTo} />}
    </div>
  );
}
