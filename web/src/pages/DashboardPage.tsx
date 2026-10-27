import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis, Legend } from 'recharts';
import { useAuth } from '../auth/AuthContext';
import type { DashboardSummary, RunSummary, TrendPoint } from '../api/types';
import { Card, ErrorBanner } from '../components/common';
import { formatPaise, formatPaiseCompact, humanize } from '../format';

function today(): string {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function dayBefore(iso: string): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}

export function DashboardPage() {
  const { api, can } = useAuth();
  const [asOf, setAsOf] = useState(today());
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [trend, setTrend] = useState<TrendPoint[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [running, setRunning] = useState(false);
  const [lastRun, setLastRun] = useState<RunSummary | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [s, t] = await Promise.all([api.dashboard(asOf), api.trend(asOf, 14)]);
      setSummary(s);
      setTrend(t);
    } catch (e) {
      setError(e);
    }
  }, [api, asOf]);

  useEffect(() => { void load(); }, [load]);

  async function runRecon() {
    setRunning(true);
    setError(null);
    try {
      setLastRun(await api.runRecon({}));
      await load();
    } catch (e) {
      setError(e);
    } finally {
      setRunning(false);
    }
  }

  const trendData = trend.map((p) => ({ date: p.businessDate.slice(5), atRisk: p.moneyAtRiskMinor, inTransit: p.expectedInTransitMinor }));
  const classData = (summary?.byClass ?? []).map((b) => ({ name: humanize(b.key), amount: b.amountMinor, count: b.count }));
  const agingData = (summary?.aging ?? []).map((b) => ({ name: b.key, amount: b.amountMinor, count: b.count }));

  return (
    <div className="page">
      <div className="page-head">
        <h1>Money at risk</h1>
        <div className="actions">
          <label className="inline">As of <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></label>
          {can('OPS_SUPERVISOR', 'ADMIN') && (
            <button className="primary" onClick={runRecon} disabled={running}>{running ? 'Reconciling…' : 'Run reconciliation'}</button>
          )}
        </div>
      </div>
      <ErrorBanner error={error} />
      {lastRun && (
        <div className="notice" role="status">
          Run #{lastRun.runId} {lastRun.status.toLowerCase()}: {lastRun.entities.toLocaleString('en-IN')} entities in {lastRun.partitions} partitions, {lastRun.durationMs} ms.
        </div>
      )}
      {summary && (
        <>
          <div className="kpis">
            <Card className="kpi danger"><div className="kpi-label">Money at risk</div><div className="kpi-value" data-testid="money-at-risk">{formatPaise(summary.moneyAtRiskMinor)}</div><div className="kpi-sub">{summary.openCount.toLocaleString('en-IN')} open exceptions</div></Card>
            <Card className="kpi"><div className="kpi-label">New (last 2 days)</div><div className="kpi-value">{formatPaise(summary.newAtRiskMinor)}</div><div className="kpi-sub">business dates {dayBefore(summary.referenceDate)} and {summary.referenceDate}</div></Card>
            <Card className="kpi"><div className="kpi-label">Expected in transit</div><div className="kpi-value">{formatPaise(summary.expectedInTransitMinor)}</div><div className="kpi-sub">{summary.pendingExpectedCount} credits inside grace window</div></Card>
            <Card className="kpi"><div className="kpi-label">Adjustments to approve</div><div className="kpi-value">{summary.adjustmentsPendingApproval}</div><div className="kpi-sub"><Link to="/exceptions?exceptionClass=AMOUNT_MISMATCH&status=OPEN">review</Link></div></Card>
          </div>
          <div className="grid2">
            <Card title="14-day trend (unresolved, by business date)">
              <div className="chart">
                <ResponsiveContainer width="100%" height={260}>
                  <LineChart data={trendData} margin={{ left: 8, right: 8, top: 8 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--grid)" />
                    <XAxis dataKey="date" fontSize={12} />
                    <YAxis yAxisId="risk" tickFormatter={formatPaiseCompact} fontSize={12} width={64} />
                    <YAxis yAxisId="transit" orientation="right" tickFormatter={formatPaiseCompact} fontSize={12} width={64} />
                    <Tooltip formatter={(v: number) => formatPaise(v)} />
                    <Legend />
                    <Line yAxisId="risk" type="monotone" dataKey="atRisk" name="At risk (left axis)" stroke="var(--danger)" strokeWidth={2} dot={{ r: 2 }} />
                    <Line yAxisId="transit" type="monotone" dataKey="inTransit" name="Expected in transit (right axis)" stroke="var(--accent)" strokeDasharray="4 3" dot={false} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </Card>
            <Card title="By exception type">
              <div className="chart">
                <ResponsiveContainer width="100%" height={260}>
                  <BarChart data={classData} layout="vertical" margin={{ left: 24, right: 16 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--grid)" />
                    <XAxis type="number" tickFormatter={formatPaiseCompact} fontSize={12} />
                    <YAxis type="category" dataKey="name" width={130} fontSize={12} />
                    <Tooltip formatter={(v: number) => formatPaise(v)} />
                    <Bar dataKey="amount" name="At risk" fill="var(--danger)" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
              <table className="mini" aria-label="breakdown by exception type">
                <tbody>
                  {summary.byClass.map((b) => (
                    <tr key={b.key}>
                      <td><Link to={`/exceptions?exceptionClass=${b.key}&status=OPEN,IN_REVIEW`}>{humanize(b.key)}</Link></td>
                      <td className="num">{b.count}</td>
                      <td className="num">{formatPaise(b.amountMinor)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
            <Card title="Aging of unresolved exceptions">
              <div className="chart">
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart data={agingData}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--grid)" />
                    <XAxis dataKey="name" fontSize={12} />
                    <YAxis tickFormatter={formatPaiseCompact} fontSize={12} width={64} />
                    <Tooltip formatter={(v: number) => formatPaise(v)} />
                    <Bar dataKey="amount" name="At risk" fill="var(--warn)" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
              <ul className="aging" aria-label="aging buckets">
                {summary.aging.map((b) => <li key={b.key}><strong>{b.key}</strong> {b.count} · {formatPaise(b.amountMinor)}</li>)}
              </ul>
            </Card>
            <Card title="Handled automatically">
              <table className="mini" aria-label="auto-fixed">
                <tbody>
                  {summary.autoFixed.length === 0 && <tr><td className="muted">Nothing auto-fixed yet.</td></tr>}
                  {summary.autoFixed.map((b) => (
                    <tr key={b.key}><td>{humanize(b.key)}</td><td className="num">{b.count.toLocaleString('en-IN')}</td></tr>
                  ))}
                </tbody>
              </table>
              {summary.lastRun && (
                <p className="muted small">
                  Last run #{String(summary.lastRun.id)} · {String(summary.lastRun.status).toLowerCase()} · {String(summary.lastRun.from_date)} → {String(summary.lastRun.to_date)} · as of {String(summary.lastRun.as_of_date)} · {String(summary.lastRun.duration_ms)} ms · parallelism {String(summary.lastRun.parallelism)}
                </p>
              )}
            </Card>
          </div>
        </>
      )}
    </div>
  );
}
