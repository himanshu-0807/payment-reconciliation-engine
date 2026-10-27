import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ExceptionView, Page } from '../api/types';
import { fakeApi, renderPage } from '../test/render';
import { ExceptionsPage } from './ExceptionsPage';

const row = (id: number, extra: Partial<ExceptionView> = {}): ExceptionView => ({
  id, entityType: 'PAYMENT', entityId: `PAY20261001-00000${id}`, businessDate: '2026-10-01', merchantId: 'M001',
  exceptionClass: 'AMOUNT_MISMATCH', reason: 'BEYOND_TOLERANCE', amountAtRiskMinor: 250075, currency: 'INR',
  status: 'OPEN', assignee: null, resolutionReason: null, createdAt: '', updatedAt: '', version: 0, ...extra,
});
const page = (content: ExceptionView[], p = 0, totalPages = 3): Page<ExceptionView> =>
  ({ content, page: p, size: 25, totalElements: 60, totalPages });

describe('ExceptionsPage', () => {
  it('lists unresolved exceptions by default', async () => {
    const api = fakeApi({ exceptions: jest.fn().mockResolvedValue(page([row(1), row(2, { status: 'IN_REVIEW', assignee: 'bob' })])) });
    renderPage(<ExceptionsPage />, { api, path: '/exceptions' });
    const table = await screen.findByRole('table', { name: 'exceptions' });
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(within(table).getAllByText('₹2,500.75')).toHaveLength(2);
    expect(within(table).getByText('In Review')).toBeInTheDocument();
    expect(api.exceptions).toHaveBeenCalledWith(expect.objectContaining({ status: 'OPEN,IN_REVIEW', page: 0, size: 25 }));
    expect(screen.getByText('60 matching')).toBeInTheDocument();
  });

  it('applies filters, converting the rupee threshold to paise', async () => {
    const api = fakeApi({ exceptions: jest.fn().mockResolvedValue(page([row(1)])) });
    renderPage(<ExceptionsPage />, { api, path: '/exceptions' });
    await screen.findByRole('table', { name: 'exceptions' });
    await userEvent.selectOptions(screen.getByLabelText('Type'), 'ORPHAN_BANK_CREDIT');
    await userEvent.type(screen.getByLabelText('Merchant'), 'M003');
    await userEvent.type(screen.getByLabelText('Min at risk (₹)'), '1,500.50');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(api.exceptions).toHaveBeenLastCalledWith(expect.objectContaining({
      exceptionClass: 'ORPHAN_BANK_CREDIT', merchantId: 'M003', minAmountMinor: 150050 })));
  });

  it('rejects a malformed amount without calling the API', async () => {
    const api = fakeApi({ exceptions: jest.fn().mockResolvedValue(page([])) });
    renderPage(<ExceptionsPage />, { api, path: '/exceptions' });
    await screen.findByText('No exceptions match these filters.');
    await userEvent.type(screen.getByLabelText('Min at risk (₹)'), '12.345');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('rupee amount');
    expect(api.exceptions).toHaveBeenCalledTimes(1);
  });

  it('pages through results', async () => {
    const api = fakeApi({ exceptions: jest.fn().mockResolvedValueOnce(page([row(1)], 0)).mockResolvedValue(page([row(9)], 1)) });
    renderPage(<ExceptionsPage />, { api, path: '/exceptions' });
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();
    expect(api.exceptions).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));
  });
});
