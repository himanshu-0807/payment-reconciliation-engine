import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ExceptionDetail } from '../api/types';
import { fakeApi, renderPage } from '../test/render';
import { ExceptionDetailPage } from './ExceptionDetailPage';

const base: ExceptionDetail = {
  exception: { id: 42, entityType: 'PAYMENT', entityId: 'PAY20261001-000123', businessDate: '2026-10-01', merchantId: 'M002',
    exceptionClass: 'AMOUNT_MISMATCH', reason: 'WITHIN_TOLERANCE', amountAtRiskMinor: 73, currency: 'INR', status: 'OPEN',
    assignee: null, resolutionReason: null, createdAt: '', updatedAt: '', version: 0 },
  reconResult: { findings: 'AMOUNT_MISMATCH:WITHIN_TOLERANCE', confidence: 'HIGH', utr: 'UTR123' },
  ledger: [{ payment_id: 'PAY20261001-000123', amount_minor: 500000, status: 'SUCCESS' }],
  gateway: [{ gateway_txn_id: 'GTX1', gross_minor: 500000, net_minor: 490000 }],
  bank: [],
  adjustment: { id: 5, exceptionId: 42, entityId: 'PAY20261001-000123', amountMinor: -73, currency: 'INR', status: 'PENDING_APPROVAL',
    createdBy: 'system', createdAt: '', approvedBy: null, approvedAt: null },
  comments: [{ id: 1, author: 'bob', body: 'checking with gateway', createdAt: '2026-10-02T10:00:00Z' }],
  history: [{ id: 1, occurredAt: '2026-10-02T09:00:00Z', actor: 'system:recon-engine', action: 'CREATED', entityType: 'EXCEPTION', entityId: '42', details: null }],
};

const render = (role: 'OPS_ANALYST' | 'OPS_SUPERVISOR', api = fakeApi({ exception: jest.fn().mockResolvedValue(base) })) => {
  renderPage(<ExceptionDetailPage />, { api, role, path: '/exceptions/42', route: '/exceptions/:id' });
  return api;
};

describe('ExceptionDetailPage', () => {
  it('shows ledger, gateway and bank side by side and flags the missing side', async () => {
    const api = render('OPS_ANALYST');
    expect(await screen.findByText('Merchant ledger')).toBeInTheDocument();
    expect(screen.getByText('Gateway settlement')).toBeInTheDocument();
    expect(screen.getByText('No bank credit received.')).toBeInTheDocument();
    expect(screen.getAllByText('₹5,000.00').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByTestId('at-risk')).toHaveTextContent('₹0.73');
    expect(screen.getByText('checking with gateway')).toBeInTheDocument();
    expect(api.exception).toHaveBeenCalledWith(42);
  });

  it('only lets supervisors approve the adjustment', async () => {
    render('OPS_ANALYST');
    expect(await screen.findByText(/needs an OPS_SUPERVISOR/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve adjustment' })).not.toBeInTheDocument();
  });

  it('approves as supervisor and reloads', async () => {
    const api = fakeApi({
      exception: jest.fn().mockResolvedValueOnce(base).mockResolvedValue({ ...base, exception: { ...base.exception, status: 'RESOLVED' },
        adjustment: { ...base.adjustment!, status: 'POSTED', approvedBy: 'alice' } }),
      approveAdjustment: jest.fn().mockResolvedValue({}),
    });
    render('OPS_SUPERVISOR', api);
    await userEvent.click(await screen.findByRole('button', { name: 'Approve adjustment' }));
    expect(api.approveAdjustment).toHaveBeenCalledWith(5);
    expect(await screen.findByText(/by alice/)).toBeInTheDocument();
  });

  it('assigns, comments and resolves', async () => {
    const api = fakeApi({ exception: jest.fn().mockResolvedValue(base), assign: jest.fn().mockResolvedValue({}),
      comment: jest.fn().mockResolvedValue({}), resolve: jest.fn().mockResolvedValue({}) });
    render('OPS_ANALYST', api);
    await userEvent.click(await screen.findByRole('button', { name: 'Assign to me' }));
    expect(api.assign).toHaveBeenCalledWith(42, 'alice');
    await userEvent.type(screen.getByLabelText('comment'), 'gateway confirmed fee');
    await userEvent.click(screen.getByRole('button', { name: 'Add comment' }));
    expect(api.comment).toHaveBeenCalledWith(42, 'gateway confirmed fee');
    await userEvent.selectOptions(screen.getByLabelText('resolution reason'), 'WRITTEN_OFF');
    await userEvent.type(screen.getByLabelText('resolution note'), 'below materiality');
    await userEvent.click(screen.getByRole('button', { name: 'Resolve' }));
    expect(api.resolve).toHaveBeenCalledWith(42, 'WRITTEN_OFF', 'below materiality');
  });

  it('asks the bank over SOAP and shows the answer', async () => {
    const api = fakeApi({ exception: jest.fn().mockResolvedValue(base),
      bankStatus: jest.fn().mockResolvedValue({ utr: 'UTR123', status: 'NOT_FOUND', creditCount: 0, amountMinor: 0, currency: 'INR', valueDate: null, bankRef: null, source: 'SOAP' }) });
    render('OPS_ANALYST', api);
    await userEvent.click(await screen.findByRole('button', { name: 'Ask bank (SOAP)' }));
    await waitFor(() => expect(screen.getByTestId('bank-status')).toHaveTextContent('Bank says Not Found'));
  });
});
