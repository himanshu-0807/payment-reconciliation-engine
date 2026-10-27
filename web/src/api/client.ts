import type {
  AdjustmentView, BankStatus, CommentView, DashboardSummary, ExceptionDetail, ExceptionFilter, ExceptionView,
  GroundTruthReport, LoginResponse, Page, Problem, RunSummary, SimRunStatus, TrendPoint,
} from './types';

/** Error carrying the server's problem+json body (code, detail, field errors). */
export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.detail || problem.code);
    this.name = 'ApiError';
  }
}

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export interface ApiOptions {
  fetch?: FetchLike;
  baseUrl?: string;
  getToken: () => string | null;
  onUnauthorized?: () => void;
}

export function query(params: object): string {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  });
  const s = q.toString();
  return s ? `?${s}` : '';
}

export function createApi(opts: ApiOptions) {
  const doFetch: FetchLike = opts.fetch ?? ((i, init) => fetch(i, init));
  const base = opts.baseUrl ?? '';

  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    const token = opts.getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    let res: Response;
    try {
      res = await doFetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch (e) {
      throw new ApiError({ status: 0, code: 'NETWORK_ERROR', detail: (e as Error).message || 'network error' });
    }
    if (res.status === 401 && path !== '/api/auth/login') opts.onUnauthorized?.();
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    const data = text ? JSON.parse(text) : undefined;
    if (!res.ok) {
      const p = (data ?? {}) as Partial<Problem>;
      throw new ApiError({ status: res.status, code: p.code ?? `HTTP_${res.status}`, detail: p.detail ?? res.statusText,
        errors: p.errors });
    }
    return data as T;
  }

  return {
    login: (username: string, password: string) =>
      request<LoginResponse>('POST', '/api/auth/login', { username, password }),
    dashboard: (asOf?: string) => request<DashboardSummary>('GET', `/api/dashboard/summary${query({ asOf })}`),
    trend: (asOf?: string, days = 14) => request<TrendPoint[]>('GET', `/api/dashboard/trend${query({ asOf, days })}`),
    exceptions: (f: ExceptionFilter) => request<Page<ExceptionView>>('GET', `/api/exceptions${query(f)}`),
    exception: (id: number) => request<ExceptionDetail>('GET', `/api/exceptions/${id}`),
    bankStatus: (id: number) => request<BankStatus>('GET', `/api/exceptions/${id}/bank-status`),
    assign: (id: number, assignee: string) => request<ExceptionView>('POST', `/api/exceptions/${id}/assign`, { assignee }),
    comment: (id: number, body: string) => request<CommentView>('POST', `/api/exceptions/${id}/comments`, { body }),
    resolve: (id: number, resolutionReason: string, note: string) =>
      request<ExceptionView>('POST', `/api/exceptions/${id}/resolve`, { resolutionReason, note }),
    approveAdjustment: (id: number) => request<AdjustmentView>('POST', `/api/adjustments/${id}/approve`),
    runRecon: (body: { fromDate?: string; toDate?: string; asOfDate?: string; parallelism?: number } = {}) =>
      request<RunSummary>('POST', '/api/recon/runs', body),
    simulatorDefaults: () => request<{ faultRates: Record<string, number> }>('GET', '/api/simulator/defaults'),
    startSimulation: (body: Record<string, unknown>) => request<SimRunStatus>('POST', '/api/simulator/runs', body),
    simulation: (id: string) => request<SimRunStatus>('GET', `/api/simulator/runs/${encodeURIComponent(id)}`),
    groundTruth: (simRunId?: string) =>
      request<GroundTruthReport>('GET', `/api/ground-truth/evaluation${query({ simRunId })}`),
  };
}

export type Api = ReturnType<typeof createApi>;
