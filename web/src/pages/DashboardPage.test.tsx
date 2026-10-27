import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DashboardSummary } from '../api/types';
import { fakeApi, renderPage } from '../test/render';
import { DashboardPage } from './DashboardPage';

const summary: DashboardSummary = {
  referenceDate: '2026-10-27', currency: 'INR', moneyAtRiskMinor: 182811255, openCount: 582,
  expectedInTransitMinor: 98000, pendingExpectedCount: 3, newAtRiskMinor: 4500, adjustmentsPendingApproval: 7,
  byClass: [{ key: 'MISSING_IN_LEDGER', count: 126, amountMinor: 100000000 }, { key: 'ORPHAN_BANK_CREDIT', count: 50, amountMinor: 82811255 }],
  aging: [{ key: '0-1d', count: 0, amountMinor: 0 }, { key: '2-3d', count: 1, amountMinor: 10 }, { key: '4-7d', count: 249, amountMinor: 1 }, { key: '8d+', count: 332, amountMinor: 182811244 }],
  byStatus: [], autoFixed: [{ key: 'DEDUPED_NO_MONEY_EFFECT', count: 241, amountMinor: 0 }],
  lastRun: { id: 4, status: 'COMPLETED', from_date: '2026-10-01', to_date: '2026-10-10', as_of_date: '2026-10-11', duration_ms: 773, parallelism: 8 },
};
const trend = Array.from({ length: 14 }, (_, i) => ({ businessDate: `2026-10-${String(i + 10).padStart(2, '0')}`, moneyAtRiskMinor: i * 1000, openCount: i, expectedInTransitMinor: 0 }));

describe('DashboardPage', () => {
  it('shows money at risk, the breakdown by type, aging and auto-fixes', async () => {
    const api = fakeApi({ dashboard: jest.fn().mockResolvedValue(summary), trend: jest.fn().mockResolvedValue(trend) });
    renderPage(<DashboardPage />, { api });
    expect(await screen.findByTestId('money-at-risk')).toHaveTextContent('₹18,28,112.55');
    expect(screen.getByText('582 open exceptions')).toBeInTheDocument();
    const breakdown = screen.getByRole('table', { name: 'breakdown by exception type' });
    expect(within(breakdown).getByRole('link', { name: 'Missing In Ledger' })).toHaveAttribute('href',
      '/exceptions?exceptionClass=MISSING_IN_LEDGER&status=OPEN,IN_REVIEW');
    expect(within(breakdown).getByText('₹10,00,000.00')).toBeInTheDocument();
    expect(screen.getByLabelText('aging buckets')).toHaveTextContent('8d+ 332');
    expect(screen.getByLabelText('auto-fixed')).toHaveTextContent('Deduped No Money Effect241');
    expect(api.trend).toHaveBeenCalledWith(expect.any(String), 14);
  });

  it('hides the run button from analysts', async () => {
    const api = fakeApi({ dashboard: jest.fn().mockResolvedValue(summary), trend: jest.fn().mockResolvedValue(trend) });
    renderPage(<DashboardPage />, { api, role: 'OPS_ANALYST' });
    await screen.findByTestId('money-at-risk');
    expect(screen.queryByRole('button', { name: 'Run reconciliation' })).not.toBeInTheDocument();
  });

  it('lets a supervisor run reconciliation and reloads the figures', async () => {
    const api = fakeApi({
      dashboard: jest.fn().mockResolvedValue(summary), trend: jest.fn().mockResolvedValue(trend),
      runRecon: jest.fn().mockResolvedValue({ runId: 9, status: 'COMPLETED', entities: 25050, partitions: 50, durationMs: 773 }),
    });
    renderPage(<DashboardPage />, { api, role: 'OPS_SUPERVISOR' });
    await userEvent.click(await screen.findByRole('button', { name: 'Run reconciliation' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Run #9 completed: 25,050 entities in 50 partitions, 773 ms.');
    expect(api.dashboard).toHaveBeenCalledTimes(2);
  });

  it('shows API errors', async () => {
    const api = fakeApi({ dashboard: jest.fn().mockRejectedValue(new Error('backend down')), trend: jest.fn().mockResolvedValue([]) });
    renderPage(<DashboardPage />, { api });
    expect(await screen.findByRole('alert')).toHaveTextContent('backend down');
  });
});
