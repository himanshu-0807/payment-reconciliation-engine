import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeApi, renderPage } from '../test/render';
import { SimulatorPage } from './SimulatorPage';

const status = (state: string) => ({ simRunId: 'sim-1', state, daysCompleted: state === 'COMPLETED' ? 2 : 1, paymentsGenerated: 4000,
  callbacksPublished: 4100, gatewayRowsWritten: 3990, bankRowsWritten: 4002, injectedFaults: {}, error: null,
  config: { seed: 7, startDate: '2026-10-01', days: 2, paymentsPerDay: 2000 } });

describe('SimulatorPage', () => {
  it('starts a run with the chosen seed and rates, polls it, reconciles and shows ground truth', async () => {
    const api = fakeApi({
      simulatorDefaults: jest.fn().mockResolvedValue({ faultRates: { DUPLICATE_CALLBACK: 0.01, MISSING_CALLBACK: 0.005 } }),
      startSimulation: jest.fn().mockResolvedValue(status('RUNNING')),
      simulation: jest.fn().mockResolvedValueOnce(status('RUNNING')).mockResolvedValue(status('COMPLETED')),
      runRecon: jest.fn().mockResolvedValue({ runId: 1, entities: 4010, durationMs: 120 }),
      groundTruth: jest.fn().mockResolvedValue({ simRunId: 'sim-1', entitiesEvaluated: 4010, injectedFaults: 60, unmappedOutcomes: 0,
        precision: 1, recall: 1, byFault: { DUPLICATE_CALLBACK: { injected: 40, detected: 40, truePositives: 40, precision: 1, recall: 1 } },
        sampleMismatches: [] }),
    });
    renderPage(<SimulatorPage pollMs={5} />, { api, role: 'ADMIN' });
    const dup = await screen.findByLabelText('DUPLICATE_CALLBACK rate');
    expect(dup).toHaveValue('1');
    await userEvent.clear(screen.getByLabelText('Seed'));
    await userEvent.type(screen.getByLabelText('Seed'), '7');
    await userEvent.clear(dup);
    await userEvent.type(dup, '2.5');
    await userEvent.click(screen.getByRole('button', { name: 'Run simulation' }));

    expect(api.startSimulation).toHaveBeenCalledWith(expect.objectContaining({ seed: 7, resetData: true,
      faultRates: { DUPLICATE_CALLBACK: 0.025, MISSING_CALLBACK: 0.005 } }));
    await waitFor(() => expect(screen.getByTestId('sim-state')).toHaveTextContent('Completed'));
    await userEvent.click(screen.getByRole('button', { name: 'Reconcile now' }));
    expect(await screen.findByTestId('gt-overall')).toHaveTextContent('Precision 100.00% · Recall 100.00% over 60 injected faults');
    expect(api.groundTruth).toHaveBeenCalledWith('sim-1');
  });

  it('validates fault rates before calling the API', async () => {
    const api = fakeApi({ simulatorDefaults: jest.fn().mockResolvedValue({ faultRates: { MISSING_CALLBACK: 0.005 } }) });
    renderPage(<SimulatorPage />, { api, role: 'ADMIN' });
    const input = await screen.findByLabelText('MISSING_CALLBACK rate');
    await userEvent.clear(input);
    await userEvent.type(input, '150');
    await userEvent.click(screen.getByRole('button', { name: 'Run simulation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('between 0 and 100');
    expect(api.startSimulation).not.toHaveBeenCalled();
  });
});
