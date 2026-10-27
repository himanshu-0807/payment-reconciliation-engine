export type Role = 'OPS_ANALYST' | 'OPS_SUPERVISOR' | 'ADMIN';

export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresAt: string;
  username: string;
  role: Role;
}

export interface Problem {
  status: number;
  code: string;
  detail: string;
  errors?: { field: string; message: string }[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type ExceptionStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'PENDING_EXPECTED' | 'AUTO_RESOLVED';

export interface ExceptionView {
  id: number;
  entityType: string;
  entityId: string;
  businessDate: string;
  merchantId: string;
  exceptionClass: string;
  reason: string;
  amountAtRiskMinor: number;
  currency: string;
  status: ExceptionStatus;
  assignee: string | null;
  resolutionReason: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface CommentView { id: number; author: string; body: string; createdAt: string }

export interface AdjustmentView {
  id: number;
  exceptionId: number;
  entityId: string;
  amountMinor: number;
  currency: string;
  status: 'POSTED' | 'PENDING_APPROVAL' | 'REJECTED';
  createdBy: string;
  createdAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
}

export interface AuditView {
  id: number;
  occurredAt: string;
  actor: string;
  action: string;
  entityType: string;
  entityId: string;
  details: string | null;
}

export type Row = Record<string, string | number | boolean | null>;

export interface ExceptionDetail {
  exception: ExceptionView;
  reconResult: Row | null;
  ledger: Row[];
  gateway: Row[];
  bank: Row[];
  adjustment: AdjustmentView | null;
  comments: CommentView[];
  history: AuditView[];
}

export interface Bucket { key: string; count: number; amountMinor: number }

export interface DashboardSummary {
  referenceDate: string;
  currency: string;
  moneyAtRiskMinor: number;
  openCount: number;
  expectedInTransitMinor: number;
  pendingExpectedCount: number;
  newAtRiskMinor: number;
  byClass: Bucket[];
  aging: Bucket[];
  byStatus: Bucket[];
  autoFixed: Bucket[];
  adjustmentsPendingApproval: number;
  lastRun: Row | null;
}

export interface TrendPoint {
  businessDate: string;
  moneyAtRiskMinor: number;
  openCount: number;
  expectedInTransitMinor: number;
}

export interface BankStatus {
  utr: string;
  status: string;
  creditCount: number;
  amountMinor: number;
  currency: string;
  valueDate: string | null;
  bankRef: string | null;
  source: string;
}

export interface RunSummary {
  runId: number;
  status: string;
  from: string;
  to: string;
  asOf: string;
  parallelism: number;
  partitions: number;
  entities: number;
  byClass: Record<string, number>;
  durationMs: number;
  digest: string;
}

export interface SimRunStatus {
  simRunId: string;
  state: 'RUNNING' | 'COMPLETED' | 'FAILED';
  daysCompleted: number;
  paymentsGenerated: number;
  callbacksPublished: number;
  gatewayRowsWritten: number;
  bankRowsWritten: number;
  injectedFaults: Record<string, number>;
  error: string | null;
  config: { seed: number; startDate: string; days: number; paymentsPerDay: number };
}

export interface FaultScore { injected: number; detected: number; truePositives: number; precision: number; recall: number }

export interface GroundTruthReport {
  simRunId: string;
  entitiesEvaluated: number;
  injectedFaults: number;
  unmappedOutcomes: number;
  precision: number;
  recall: number;
  byFault: Record<string, FaultScore>;
  sampleMismatches: { entityType: string; entityId: string; expected: string; actual: string }[];
}

export interface ExceptionFilter {
  exceptionClass?: string;
  status?: string;
  merchantId?: string;
  from?: string;
  to?: string;
  minAmountMinor?: number;
  entityId?: string;
  sort?: string;
  page?: number;
  size?: number;
}
