import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Api } from '../api/client';
import type { Role } from '../api/types';
import { AuthProvider } from '../auth/AuthContext';

export type FakeApi = { [K in keyof Api]: jest.Mock };

export function fakeApi(overrides: Partial<FakeApi> = {}): FakeApi {
  const names: (keyof Api)[] = ['login', 'dashboard', 'trend', 'exceptions', 'exception', 'bankStatus', 'assign', 'comment',
    'resolve', 'approveAdjustment', 'runRecon', 'simulatorDefaults', 'startSimulation', 'simulation', 'groundTruth'];
  const api = {} as FakeApi;
  names.forEach((n) => { api[n] = jest.fn().mockRejectedValue(new Error(`${n} not stubbed`)); });
  return Object.assign(api, overrides);
}

/** Renders a page inside AuthProvider (signed in as `role`) and a router at `path`. */
export function renderPage(ui: ReactElement, opts: { api: FakeApi; role?: Role | null; path?: string; route?: string }) {
  sessionStorage.clear();
  if (opts.role !== null) {
    sessionStorage.setItem('recon.session', JSON.stringify({ token: 't', username: 'alice', role: opts.role ?? 'OPS_ANALYST' }));
  }
  const factory = (() => opts.api) as unknown as typeof import('../api/client').createApi;
  return render(
    <MemoryRouter initialEntries={[opts.path ?? '/']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider apiFactory={factory}>
        <Routes>
          <Route path={opts.route ?? '*'} element={ui} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}
