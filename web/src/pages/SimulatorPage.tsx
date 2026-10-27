import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { GroundTruthReport, RunSummary, SimRunStatus } from '../api/types';
import { Card, ErrorBanner } from '../components/common';
import { humanize } from '../format';

const pct = (x: number) => `${(x * 100).toFixed(2)}%`;

export function SimulatorPage({ pollMs = 1000 }: { pollMs?: number }) {
  const { api } = useAuth();
  const [seed, setSeed] = useState('42');
  const [days, setDays] = useState('14');
  const [perDay, setPerDay] = useState('2000');
  const [merchants, setMerchants] = useState('5');
  const [rates, setRates] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<SimRunStatus | null>(null);
  const [run, setRun] = useState<RunSummary | null>(null);
  const [gt, setGt] = useState<GroundTruthReport | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    api.simulatorDefaults()
      .then((d) => setRates(Object.fromEntries(Object.entries(d.faultRates).map(([k, v]) => [k, String(+(v * 100).toFixed(3))]))))
      .catch(setError);
    return () => clearTimeout(timer.current);
  }, [api]);

  function poll(id: string) {
    timer.current = setTimeout(async () => {
      try {
        const s = await api.simulation(id);
        setStatus(s);
        if (s.state === 'RUNNING') poll(id);
        else setBusy(false);
      } catch (e) {
        setError(e);
        setBusy(false);
      }
    }, pollMs);
  }

  async function start(ev: FormEvent) {
    ev.preventDefault();
    setError(null);
    setRun(null);
    setGt(null);
    const faultRates: Record<string, number> = {};
    for (const [k, v] of Object.entries(rates)) {
      const n = Number(v);
      if (!Number.isFinite(n) || n < 0 || n > 100) {
        setError(new Error(`${humanize(k)}: rate must be a percentage between 0 and 100`));
        return;
      }
      faultRates[k] = n / 100;
    }
    setBusy(true);
    try {
      const s = await api.startSimulation({ seed: Number(seed), days: Number(days), paymentsPerDay: Number(perDay),
        merchants: Number(merchants), faultRates, resetData: true });
      setStatus(s);
      poll(s.simRunId);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  async function reconcile() {
    setBusy(true);
    setError(null);
    try {
      setRun(await api.runRecon({ parallelism: 8 }));
      setGt(await api.groundTruth(status?.simRunId));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <div className="page-head"><h1>Failure simulator</h1></div>
      <p className="muted">Generates synthetic payments from a seed, injects faults at the rates below and writes the ground truth. Starting a run clears previous data.</p>
      <ErrorBanner error={error} />
      <form className="card sim-form" onSubmit={start} aria-label="simulation settings">
        <div className="row wrap">
          <label>Seed<input value={seed} onChange={(e) => setSeed(e.target.value)} inputMode="numeric" /></label>
          <label>Days<input value={days} onChange={(e) => setDays(e.target.value)} inputMode="numeric" /></label>
          <label>Payments / day<input value={perDay} onChange={(e) => setPerDay(e.target.value)} inputMode="numeric" /></label>
          <label>Merchants<input value={merchants} onChange={(e) => setMerchants(e.target.value)} inputMode="numeric" /></label>
        </div>
        <table className="mini rates" aria-label="fault rates">
          <thead><tr><th>Fault</th><th>Rate (%)</th></tr></thead>
          <tbody>
            {Object.entries(rates).map(([k, v]) => (
              <tr key={k}><td>{humanize(k)}</td><td><input aria-label={`${k} rate`} value={v} onChange={(e) => setRates({ ...rates, [k]: e.target.value })} inputMode="decimal" /></td></tr>
            ))}
          </tbody>
        </table>
        <button type="submit" className="primary" disabled={busy}>Run simulation</button>
      </form>
      {status && (
        <Card title={`Simulation ${status.simRunId}`}>
          <p data-testid="sim-state">{humanize(status.state)} · day {status.daysCompleted}/{status.config.days} · {status.paymentsGenerated.toLocaleString('en-IN')} payments · {status.callbacksPublished.toLocaleString('en-IN')} callbacks · {status.bankRowsWritten.toLocaleString('en-IN')} bank rows</p>
          {status.error && <ErrorBanner error={new Error(status.error)} />}
          {status.state === 'COMPLETED' && <button className="primary" disabled={busy} onClick={reconcile}>Reconcile now</button>}
          {run && <p role="status">Reconciled {run.entities.toLocaleString('en-IN')} entities in {run.durationMs} ms. <Link to="/">Open dashboard</Link></p>}
        </Card>
      )}
      {gt && (
        <Card title="Engine vs ground truth">
          <p data-testid="gt-overall">Precision <strong>{pct(gt.precision)}</strong> · Recall <strong>{pct(gt.recall)}</strong> over {gt.injectedFaults.toLocaleString('en-IN')} injected faults ({gt.entitiesEvaluated.toLocaleString('en-IN')} entities).</p>
          {gt.unmappedOutcomes > 0 && <p className="muted small">{gt.unmappedOutcomes} outcomes are still undecided (e.g. credits not yet due inside the grace window) and count against precision.</p>}
          <table className="mini" aria-label="ground truth by fault">
            <thead><tr><th>Fault</th><th className="num">Injected</th><th className="num">Detected</th><th className="num">Precision</th><th className="num">Recall</th></tr></thead>
            <tbody>
              {Object.entries(gt.byFault).map(([k, s]) => (
                <tr key={k}><td>{humanize(k)}</td><td className="num">{s.injected}</td><td className="num">{s.detected}</td><td className="num">{pct(s.precision)}</td><td className="num">{pct(s.recall)}</td></tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}
